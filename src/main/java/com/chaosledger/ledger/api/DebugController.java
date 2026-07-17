package com.chaosledger.ledger.api;

import com.chaosledger.ledger.infrastructure.eventstore.EventEntity;
import com.chaosledger.ledger.infrastructure.eventstore.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Debug endpoints for multi-node verification.
 *
 * Added in Week 10 to make cross-node HLC verification possible from tests.
 * The normal REST API (AccountController, HlcStatusController) exposes domain
 * state and current clock state — neither exposes per-event HLC metadata.
 *
 * GET /api/debug/events?limit=100
 *   Returns the most-recently-created events with their HLC columns.
 *   Used by HlcCrossNodeTest to verify that all 3 nodes stored the same
 *   HLC timestamps for the same events.
 *
 * GET /api/debug/events/count
 *   Returns the total event count on this node.
 *   Used by MultiNodeIntegrationTest to confirm replication completed.
 *
 * This controller is NOT conditional on raft.enabled — it works in both
 * single-node and multi-node mode. In single-node mode it just reports
 * whatever the local Postgres contains.
 */
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
@Slf4j
public class DebugController {

    private final EventRepository eventRepository;

    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> listEvents(
            @RequestParam(defaultValue = "100") int limit) {

        // Sort by (hlc_physical_time, hlc_logical_counter, hlc_node_id) — the
        // canonical HLC total order. Aggregate version alone is per-aggregate
        // and does not order events across accounts.
        var page = eventRepository.findAll(
                PageRequest.of(0, limit,
                        Sort.by(Sort.Direction.ASC,
                                "hlcPhysicalTime",
                                "hlcLogicalCounter",
                                "hlcNodeId")));

        List<Map<String, Object>> body = page.getContent().stream()
                .map(this::toMap)
                .toList();

        return ResponseEntity.ok(body);
    }

    @GetMapping("/events/count")
    public ResponseEntity<Map<String, Object>> countEvents() {
        long count = eventRepository.count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        body.put("at", Instant.now());
        return ResponseEntity.ok(body);
    }

    // ── Helpers ────────────────────────────────────────────────

    private Map<String, Object> toMap(EventEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", e.getId());
        row.put("aggregateId", e.getAggregateId());
        row.put("eventType", e.getEventType());
        row.put("version", e.getVersion());
        row.put("hlcPhysicalTime", e.getHlcPhysicalTime());
        row.put("hlcLogicalCounter", e.getHlcLogicalCounter());
        row.put("hlcNodeId", e.getHlcNodeId());
        row.put("createdAt", e.getCreatedAt());
        return row;
    }

    /**
     * A stable identity for cross-node comparison. Two rows with the same
     * eventId on different nodes MUST have identical HLC triples if Week 9's
     * appendWithHlc() is working correctly. This method is used by tests to
     * flatten a row into a comparable key.
     */
    public static String hlcKey(UUID eventId, Long physical, Integer logical, String nodeId) {
        return eventId + "|" + physical + ":" + logical + "@" + nodeId;
    }
}