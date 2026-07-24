package com.chaosledger.ledger.infrastructure.websocket;

import com.chaosledger.ledger.api.dto.DashboardDto;
import com.chaosledger.ledger.api.dto.DashboardDto.RaftNodeSnapshot;
import com.chaosledger.ledger.domain.invariants.InvariantResult;
import com.chaosledger.ledger.infrastructure.eventstore.EventEntity;
import com.chaosledger.ledger.infrastructure.eventstore.EventRepository;
import com.chaosledger.ledger.infrastructure.invariants.InvariantCheckerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Week 14 — Pushes an aggregated DashboardDto to /topic/dashboard every 2 seconds.
 *
 * This is the single source of truth for the Trust Dashboard. It reads:
 *   - Raft status from the local RaftServer (role, term, leader)
 *   - Invariant results from InvariantCheckerService
 *   - Recent events from EventRepository
 *   - Chaos events (if chaos is enabled)
 *
 * The scheduler runs on EVERY node, but only the leader's dashboard
 * has the most complete view. The frontend connects to one node
 * (typically the leader) and gets everything it needs.
 *
 * Why @Scheduled instead of event-driven?
 * The dashboard needs a periodic snapshot regardless of whether anything
 * changed — it needs to show "still healthy" as much as "something broke."
 * Event-driven would miss the "no news is good news" signal.
 */
@Component
@Slf4j
public class DashboardStreamScheduler {

    private final SimpMessagingTemplate messagingTemplate;
    private final InvariantCheckerService invariantChecker;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate = createTimedRestTemplate();

    private static RestTemplate createTimedRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return new RestTemplate(factory);
    }

    private static final List<String> NODE_URLS = List.of(
            "http://ledger-1:8080",
            "http://ledger-2:8081",
            "http://ledger-3:8082"
    );
    private static final List<String> LOCAL_NODE_URLS = List.of(
            "http://localhost:8080",
            "http://localhost:8081",
            "http://localhost:8082"
    );

    private long previousEventCount = 0;
    private long previousTimestampMs = System.currentTimeMillis();

    // Nullable — only present when raft.enabled=true
    @Autowired(required = false)
    private RaftServer raftServer;

    public DashboardStreamScheduler(
            SimpMessagingTemplate messagingTemplate,
            InvariantCheckerService invariantChecker,
            EventRepository eventRepository,
            ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.invariantChecker = invariantChecker;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs every 2 seconds. Gathers all cluster state into one DTO
     * and broadcasts it to every connected dashboard client.
     */
    @Scheduled(fixedRate = 2000, initialDelay = 5000)
    public void pushDashboardState() {
        try {
            DashboardDto dto = buildSnapshot();
            messagingTemplate.convertAndSend("/topic/dashboard", dto);
            log.trace("Dashboard snapshot pushed ({} events, {} invariants)",
                    dto.eventCount(), dto.invariants().size());
        } catch (Exception e) {
            log.warn("Failed to push dashboard snapshot: {}", e.getMessage());
        }
    }

    // Build the snapshot

    private DashboardDto buildSnapshot() {
        long currentCount = eventRepository.count();
        Map<String, Object> metrics = computeMetrics(currentCount);
        List<RaftNodeSnapshot> raftNodes = getRaftNodes();

        String currentLeader = raftNodes.stream()
                .filter(n -> "LEADER".equals(n.role()))
                .map(RaftNodeSnapshot::nodeId)
                .findFirst().orElse("unknown");

        return new DashboardDto(
                raftNodes,
                invariantChecker.getStatus(),
                getInvariantResults(),
                currentCount,
                getRecentEvents(20, currentLeader),
                Collections.emptyList(),
                getHlcStatus(),
                metrics,
                Instant.now()
        );
    }

    // Raft status (all 3 nodes)

    @SuppressWarnings("unchecked")
    private List<RaftNodeSnapshot> getRaftNodes() {
        if (raftServer == null) {
            return List.of(new RaftNodeSnapshot(
                    "node-1", "LEADER", "node-1", 1, 0, 0
            ));
        }

        List<RaftNodeSnapshot> results = new ArrayList<>();

        for (int i = 0; i < NODE_URLS.size(); i++) {
            try {
                Map<String, Object> status = fetchNodeStatus(NODE_URLS.get(i));
                if (status == null) status = fetchNodeStatus(LOCAL_NODE_URLS.get(i));
                if (status != null) {
                    results.add(toSnapshot(status));
                    continue;
                }
            } catch (Exception ignored) {}

            try {
                Map<String, Object> status = fetchNodeStatus(LOCAL_NODE_URLS.get(i));
                if (status != null) {
                    results.add(toSnapshot(status));
                    continue;
                }
            } catch (Exception ignored) {}

            results.add(new RaftNodeSnapshot(
                    "node-" + (i + 1), "UNREACHABLE", "unknown", 0, 0, 0
            ));
        }

        return results;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchNodeStatus(String baseUrl) {
        try {
            return restTemplate.getForObject(baseUrl + "/api/raft/status", Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private RaftNodeSnapshot toSnapshot(Map<String, Object> status) {
        return new RaftNodeSnapshot(
                String.valueOf(status.getOrDefault("nodeId", "unknown")),
                String.valueOf(status.getOrDefault("role", "FOLLOWER")),
                String.valueOf(status.getOrDefault("leaderId", "unknown")),
                toLong(status.get("term")),
                toLong(status.get("commitIndex")),
                toLong(status.get("logIndex"))
        );
    }

    private long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    // Invariants

    private List<Map<String, Object>> getInvariantResults() {
        List<Map<String, Object>> results = new ArrayList<>();
        for (InvariantResult r : invariantChecker.getLatestResults()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", r.name());
            map.put("status", r.status().name());
            map.put("message", r.message());
            map.put("checkedAt", r.checkedAt() != null ? r.checkedAt().toString() : null);
            map.put("durationMs", r.duration() != null ? r.duration().toMillis() : 0);
            results.add(map);
        }
        return results;
    }

    // Recent events

    private List<Map<String, Object>> getRecentEvents(int limit, String currentLeader) {
        var page = eventRepository.findAll(
                PageRequest.of(0, limit,
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> events = new ArrayList<>();
        for (EventEntity e : page.getContent()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("eventId", e.getId().toString());
            map.put("aggregateId", e.getAggregateId().toString());
            map.put("eventType", e.getEventType());
            map.put("version", e.getVersion());
            map.put("createdAt", e.getCreatedAt().toString());
            map.put("hlcPhysicalTime", e.getHlcPhysicalTime());
            map.put("hlcLogicalCounter", e.getHlcLogicalCounter());
            map.put("hlcNodeId", e.getHlcNodeId());
            map.put("leader", currentLeader);
            if (e.getPayload() != null && e.getPayload().has("amount")) {
                map.put("amount", e.getPayload().get("amount").asDouble());
            }
            events.add(map);
        }
        return events;
    }

    // HLC status

    private Map<String, Object> getHlcStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("timestamp", Instant.now().toString());
        return status;
    }

    // Live metrics

    private Map<String, Object> computeMetrics(long currentEventCount) {
        long now = System.currentTimeMillis();
        long elapsed = now - previousTimestampMs;
        long delta = currentEventCount - previousEventCount;

        double tps = elapsed > 0 ? (delta * 1000.0) / elapsed : 0;

        previousEventCount = currentEventCount;
        previousTimestampMs = now;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tps", Math.round(tps));
        m.put("totalEvents", currentEventCount);

        Runtime rt = Runtime.getRuntime();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        m.put("memoryUsedMb", usedMem / (1024 * 1024));
        m.put("memoryTotalMb", rt.totalMemory() / (1024 * 1024));
        m.put("memoryPercent", rt.totalMemory() > 0 ? (usedMem * 100) / rt.totalMemory() : 0);
        m.put("availableProcessors", rt.availableProcessors());

        return m;
    }
}
