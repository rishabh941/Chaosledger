package com.chaosledger.ledger.api;

import com.chaosledger.ledger.infrastructure.invariants.InvariantCheckerService;
import com.chaosledger.ledger.infrastructure.eventstore.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Week 14 — REST endpoint the dashboard calls on initial mount
 * to get the full cluster state before the WebSocket connection
 * is established.
 *
 * GET /api/dashboard/snapshot
 *   Aggregates Raft status from ALL 3 nodes (cross-node HTTP calls),
 *   invariant results, event count, and chaos status.
 *
 * GET /api/dashboard/nodes
 *   Returns Raft status from all 3 nodes for the cluster topology.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final InvariantCheckerService invariantChecker;
    private final EventRepository eventRepository;

    // The node HTTP ports within Docker Compose network
    // These match docker-compose.chaos.yml: ledger-1:8080, ledger-2:8081, ledger-3:8082
    private static final List<String> NODE_URLS = List.of(
            "http://ledger-1:8080",
            "http://ledger-2:8081",
            "http://ledger-3:8082"
    );

    // For running outside Docker (local dev), fall back to localhost
    private static final List<String> LOCAL_NODE_URLS = List.of(
            "http://localhost:8080",
            "http://localhost:8081",
            "http://localhost:8082"
    );

    private final RestTemplate restTemplate = createTimedRestTemplate();

    private static RestTemplate createTimedRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return new RestTemplate(factory);
    }

    /**
     * GET /api/dashboard/nodes
     *
     * Calls /api/raft/status on each of the 3 nodes and returns
     * all three responses. This is what the cluster topology
     * visualization consumes.
     */
    @GetMapping("/nodes")
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<Map<String, Object>>> getNodes() {
        List<Map<String, Object>> results = new ArrayList<>();

        for (String baseUrl : NODE_URLS) {
            try {
                Map<String, Object> status = restTemplate.getForObject(
                        baseUrl + "/api/raft/status", Map.class);
                if (status != null) {
                    results.add(status);
                }
            } catch (Exception e) {
                log.warn("Could not reach {}: {}", baseUrl, e.getMessage());
                // Try localhost fallback
                try {
                    String localUrl = baseUrl
                            .replace("ledger-1", "localhost")
                            .replace("ledger-2", "localhost")
                            .replace("ledger-3", "localhost");
                    Map<String, Object> status = restTemplate.getForObject(
                            localUrl + "/api/raft/status", Map.class);
                    if (status != null) {
                        results.add(status);
                    }
                } catch (Exception e2) {
                    // Node is truly unreachable
                    Map<String, Object> down = new LinkedHashMap<>();
                    down.put("nodeId", "node-" + (results.size() + 1));
                    down.put("role", "UNREACHABLE");
                    down.put("error", e.getMessage());
                    results.add(down);
                }
            }
        }

        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/dashboard/snapshot
     *
     * Full cluster snapshot for the dashboard initial load.
     * Combines nodes + invariants + event count.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // Nodes
        snapshot.put("nodes", getNodes().getBody());

        // Invariants
        snapshot.put("invariantStatus", invariantChecker.getStatus());
        snapshot.put("invariants", invariantChecker.getLatestResults());

        // Events
        snapshot.put("eventCount", eventRepository.count());

        // Timestamp
        snapshot.put("timestamp", java.time.Instant.now().toString());

        return ResponseEntity.ok(snapshot);
    }
}
