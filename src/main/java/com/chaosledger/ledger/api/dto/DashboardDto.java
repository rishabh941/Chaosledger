package com.chaosledger.ledger.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Week 14 — Aggregated snapshot of cluster state for the Trust Dashboard.
 *
 * One DTO, one WebSocket message, one atomic picture of the cluster.
 * This avoids the frontend having to poll 5 separate endpoints and
 * deal with ordering/consistency between them.
 *
 * Every field maps directly to an existing API endpoint:
 *   raftNodes    ← GET /api/raft/status      (on each of the 3 nodes)
 *   invariants   ← GET /api/invariants        (on the leader)
 *   eventCount   ← GET /api/debug/events/count
 *   recentEvents ← GET /api/debug/events?limit=20
 *   chaosEvents  ← GET /api/chaos/status      (eventLog array)
 *   hlcStatus    ← GET /api/hlc/status
 */
public record DashboardDto(

        // Raft state for all 3 nodes
        List<RaftNodeSnapshot> raftNodes,

        // Invariant checker results
        Map<String, Object> invariantStatus,
        List<Map<String, Object>> invariants,

        // Transaction / event counts
        long eventCount,
        List<Map<String, Object>> recentEvents,

        // Chaos engine
        List<Map<String, Object>> chaosEvents,

        // HLC
        Map<String, Object> hlcStatus,

        // Live metrics
        Map<String, Object> metrics,

        // Timestamp of this snapshot
        Instant timestamp

) {
    public record RaftNodeSnapshot(
            String nodeId,
            String role,
            String leaderId,
            long term,
            long commitIndex,
            long logIndex
    ) {}
}
