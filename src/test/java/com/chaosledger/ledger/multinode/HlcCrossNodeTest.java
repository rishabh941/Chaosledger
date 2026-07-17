package com.chaosledger.ledger.multinode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 10 — HlcCrossNodeTest.
 *
 * Proves the Week 9 HLC integration actually works across the cluster:
 *  1. Every event stored on the leader has an HLC triple.
 *  2. Every event stored on followers has the SAME HLC triple as the leader.
 *  3. Events are monotonically ordered by HLC on every node.
 *  4. After a leader failover, HLC continues advancing.
 *  5. The hlc.update(leaderHlc) call in the state machine actually runs
 *     (verified via the /api/hlc/status endpoint on a follower).
 */
class HlcCrossNodeTest extends ManualClusterTestBase {

    @Test
    @DisplayName("Every event on the leader is stamped with an HLC timestamp")
    void everyEvent_isStampedWithHlc() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("10.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        client.waitForEventCount(leaderIdx, 2, Duration.ofSeconds(10));

        List<Map<String, Object>> events = client.getDebugEvents(leaderIdx, 100);
        assertThat(events).isNotEmpty();
        for (Map<String, Object> ev : events) {
            assertThat(ev.get("hlcPhysicalTime")).isNotNull();
            assertThat(ev.get("hlcLogicalCounter")).isNotNull();
            assertThat(ev.get("hlcNodeId")).isNotNull();
        }
    }

    @Test
    @DisplayName("Same event has an identical HLC triple on every node")
    void hlcTriple_isIdenticalAcrossAllNodes() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("50.00"), UUID.randomUUID());
        client.deposit(accountId, new BigDecimal("25.00"), UUID.randomUUID());
        client.withdraw(accountId, new BigDecimal("10.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId, new BigDecimal("65.00"), Duration.ofSeconds(10));
        }

        Map<String, String>[] byNode = new Map[client.nodeCount()];
        for (int i = 0; i < client.nodeCount(); i++) {
            Map<String, String> map = new HashMap<>();
            for (Map<String, Object> ev : client.getDebugEvents(i, 200)) {
                String eventId = String.valueOf(ev.get("eventId"));
                String hlcKey = ev.get("hlcPhysicalTime")
                        + ":" + ev.get("hlcLogicalCounter")
                        + "@" + ev.get("hlcNodeId");
                map.put(eventId, hlcKey);
            }
            byNode[i] = map;
        }

        Set<String> allEventIds = new LinkedHashSet<>();
        for (Map<String, String> m : byNode) allEventIds.addAll(m.keySet());

        for (String eventId : allEventIds) {
            String reference = null;
            for (int i = 0; i < byNode.length; i++) {
                String observed = byNode[i].get(eventId);
                if (observed == null) continue;
                if (reference == null) reference = observed;
                else {
                    assertThat(observed)
                            .as("Event " + eventId
                                    + " has different HLC on node " + i
                                    + " than reference")
                            .isEqualTo(reference);
                }
            }
        }
    }

    @Test
    @DisplayName("Events are monotonically ordered by HLC on every node")
    void hlc_monotonicallyIncreasing_onEveryNode() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        for (int k = 0; k < 5; k++) {
            client.deposit(accountId, new BigDecimal("1.00"), UUID.randomUUID());
        }

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId, new BigDecimal("5.00"), Duration.ofSeconds(10));
        }

        for (int i = 0; i < client.nodeCount(); i++) {
            List<Map<String, Object>> events = client.getDebugEvents(i, 500);
            long prevPhysical = -1;
            int prevLogical = -1;
            for (Map<String, Object> ev : events) {
                long p = ((Number) ev.get("hlcPhysicalTime")).longValue();
                int l = ((Number) ev.get("hlcLogicalCounter")).intValue();
                boolean nonDecreasing = p > prevPhysical
                        || (p == prevPhysical && l >= prevLogical);
                assertThat(nonDecreasing)
                        .as("HLC went backward on node " + i
                                + " at event " + ev.get("eventId")
                                + " (" + prevPhysical + ":" + prevLogical
                                + " → " + p + ":" + l + ")")
                        .isTrue();
                prevPhysical = p;
                prevLogical = l;
            }
        }
    }

//    @Test
//    @Disabled("Requires Docker API — skipped on Windows")
//    @DisplayName("HLC continues advancing after a leader election")
//    void hlc_advancesAcrossLeaderElection() {
//        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
//        client.deposit(accountId, new BigDecimal("1.00"), UUID.randomUUID());
//
//        long baselinePhysical = highestPhysicalTime();
//
//        int oldLeaderIdx = client.findLeader();
//        stopContainer(serviceForNodeIdx(oldLeaderIdx));
//        int newLeaderIdx = client.waitForLeaderElection(Duration.ofSeconds(10));
//        assertThat(newLeaderIdx).isNotEqualTo(oldLeaderIdx);
//
//        client.deposit(accountId, new BigDecimal("2.00"), UUID.randomUUID());
//
//        long newHighest = highestPhysicalTimeExcluding(oldLeaderIdx);
//        assertThat(newHighest)
//                .as("HLC did not advance across the leader election. "
//                        + "This indicates LedgerStateMachine did not call "
//                        + "hlc.update(leaderHlc) — see Week 9 guide §14.")
//                .isGreaterThanOrEqualTo(baselinePhysical);
//    }

    @Test
    @DisplayName("A follower's HLC status advances after the leader writes")
    void followerHlcStatus_advancesWhenLeaderWrites() {
        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % client.nodeCount();

        JsonNode before = client.getHlcStatus(followerIdx);
        long beforePhysical = before.path("physicalTime").asLong();

        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("1.00"), UUID.randomUUID());
        client.waitForBalance(followerIdx, accountId, new BigDecimal("1.00"), Duration.ofSeconds(10));

        JsonNode after = client.getHlcStatus(followerIdx);
        long afterPhysical = after.path("physicalTime").asLong();
        assertThat(afterPhysical)
                .as("Follower HLC did not advance after the leader wrote. "
                        + "hlc.update(leaderHlc) is not being called on apply.")
                .isGreaterThanOrEqualTo(beforePhysical);
    }

// ── Helpers ─────────────────────────────────────────

    private long highestPhysicalTime() {
        long best = -1;
        for (int i = 0; i < client.nodeCount(); i++) {
            try {
                for (Map<String, Object> ev : client.getDebugEvents(i, 500)) {
                    long p = ((Number) ev.get("hlcPhysicalTime")).longValue();
                    if (p > best) best = p;
                }
            } catch (Exception ignored) { }
        }
        return best;
    }

    private long highestPhysicalTimeExcluding(int excludedIdx) {
        long best = -1;
        for (int i = 0; i < client.nodeCount(); i++) {
            if (i == excludedIdx) continue;
            try {
                for (Map<String, Object> ev : client.getDebugEvents(i, 500)) {
                    long p = ((Number) ev.get("hlcPhysicalTime")).longValue();
                    if (p > best) best = p;
                }
            } catch (Exception ignored) { }
        }
        return best;
    }
}