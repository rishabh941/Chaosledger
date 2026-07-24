package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 13 — Scenarios 6–10 of the chaos catalogue.
 *
 * Same discipline as Week 12's CatalogScenarioTest:
 *   1. Hypothesis  2. Steady state  3. Inject fault
 *   4. Verify hypothesis  5. Heal  6. Verify recovery
 *
 * Scenario 5.3 (poison-pill-message) requires Kafka, which is not yet
 * wired into ChaosLedger. Per the Week 12 guide's own guidance — "if
 * Kafka is not ready, scenario 5.3 can be skipped or stubbed" — it is
 * stubbed below as a disabled test with a clear reason, not silently
 * dropped from the catalogue.
 *
 * Run with: RUN_MULTINODE_TESTS=true mvn test -Dtest=Week13ScenarioTest
 */
@EnabledIfEnvironmentVariable(named = "RUN_MULTINODE_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Week13ScenarioTest extends ChaosTestBase {

    // Scenario 2.2 — Asymmetric Partition
    //
    // Hypothesis: If a follower can send Raft messages but not receive
    //   them (or vice versa), the cluster still converges on a single
    //   leader and continues accepting writes.
    //
    // Failure mode: One-directional network failure — asymmetric
    //   routing, a one-way firewall rule, or a NIC with a stuck
    //   receive queue.

    @Test
    @Order(1)
    @DisplayName("2.2 asymmetric-partition")
    void scenario_2_2_asymmetricPartition() {
        // Steady state
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("12000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("12000.00"), Duration.ofSeconds(10));
        }

        // Target a follower, not the leader, so writes can keep flowing
        int targetIdx = (leaderIdx + 1) % 3;
        int stableIdx = (leaderIdx + 2) % 3;
        int targetNodeId = nodeIdFromIdx(targetIdx);

        // Inject: target node can SEND but cannot RECEIVE Raft traffic
        chaosEngine.partitionAsymmetric(targetNodeId, ChaosEngine.AsymmetricDirection.CANNOT_RECEIVE);
        sleep(3000); // let Raft heartbeats notice the one-way failure

        // Verify: exactly one leader; writes still succeed
        int leaderDuringFault = client.findLeader();
        client.deposit(account, new BigDecimal("500.00"), UUID.randomUUID());

        client.waitForBalance(leaderDuringFault, account,
                new BigDecimal("12500.00"), Duration.ofSeconds(10));
        client.waitForBalance(stableIdx, account,
                new BigDecimal("12500.00"), Duration.ofSeconds(10));

        int leaderCount = 0;
        for (int i = 0; i < 3; i++) {
            JsonNode s = client.getRaftStatus(i);
            if ("LEADER".equals(s.path("role").asText(""))) leaderCount++;
        }
        assertThat(leaderCount)
                .as("Exactly one leader during asymmetric partition")
                .isEqualTo(1);

        // Heal
        chaosEngine.healAsymmetricPartition(targetNodeId);
        sleep(5000);

        // Verify recovery
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("12500.00"), Duration.ofSeconds(20));
        }
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[2.2] asymmetric-partition PASSED.");
    }

    // Scenario 2.7 — Split-Brain With Write Acceptance
    //
    // Hypothesis: While the old leader is isolated, no two nodes ever
    //   simultaneously report LEADER, and any write sent directly to
    //   the isolated old leader is never durably reflected on the
    //   surviving quorum.
    //
    // Failure mode: Leader network partition, instrumented during the
    //   fault window instead of only after recovery.

    @Test
    @Order(2)
    @DisplayName("2.7 split-brain-with-write-acceptance")
    void scenario_2_7_splitBrainWithWriteAcceptance() {
        // Steady state
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("20000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("20000.00"), Duration.ofSeconds(10));
        }

        // ── Inject: isolate the leader's Raft traffic (HTTP stays reachable
        //    so we can directly probe write acceptance on the old leader) ──
        chaosEngine.partitionNode(leaderNodeId);

        // Sample all 3 nodes' role for a window; at no instant should two
        // nodes simultaneously report LEADER
        int maxSimultaneousLeaders = 0;
        long sampleStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - sampleStart < 8000) {
            int leadersNow = 0;
            for (int i = 0; i < 3; i++) {
                try {
                    JsonNode s = client.getRaftStatus(i);
                    if ("LEADER".equals(s.path("role").asText(""))) leadersNow++;
                } catch (Exception ignored) {}
            }
            maxSimultaneousLeaders = Math.max(maxSimultaneousLeaders, leadersNow);
            sleep(200);
        }
        assertThat(maxSimultaneousLeaders)
                .as("At most one node should ever report LEADER — Raft must prevent split-brain")
                .isLessThanOrEqualTo(1);

        // Attempt a direct write against the isolated old leader. It must
        // not end up durably committed on the surviving quorum.
        UUID rogueKey = UUID.randomUUID();
        boolean rogueWriteAccepted;
        try {
            client.depositDirect(leaderIdx, account, new BigDecimal("999.00"), rogueKey);
            rogueWriteAccepted = true;
        } catch (Exception e) {
            rogueWriteAccepted = false;
            System.out.printf("[2.7] Rogue write against isolated old leader correctly failed: %s%n",
                    e.getMessage());
        }

        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;
        int newLeaderIdx = -1;
        long electionStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - electionStart < 15000) {
            for (int idx : new int[]{survivor1, survivor2}) {
                try {
                    JsonNode s = client.getRaftStatus(idx);
                    if ("LEADER".equals(s.path("role").asText(""))) { newLeaderIdx = idx; break; }
                } catch (Exception ignored) {}
            }
            if (newLeaderIdx >= 0) break;
            sleep(250);
        }
        assertThat(newLeaderIdx).as("Survivors elect a new leader").isGreaterThanOrEqualTo(0);

        // The rogue write must not be reflected on the surviving quorum
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal balance = client.getBalance(idx, account);
            assertThat(balance)
                    .as("Rogue write on the isolated old leader must not be committed on node %d", idx)
                    .isEqualByComparingTo(new BigDecimal("20000.00"));
        }

        // Heal
        chaosEngine.healPartition(leaderNodeId);
        sleep(8000);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("20000.00"), Duration.ofSeconds(20));
        }
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.printf("[2.7] split-brain-with-write-acceptance PASSED. Rogue write accepted locally: %s%n",
                rogueWriteAccepted);
    }

    // Scenario 3.1 — Clock Drift Forward 2s
    //
    // Hypothesis: A node whose wall clock drifts 2s forward has its
    //   HLC absorb the skew without going backward, without corrupting
    //   replication, and without regressing once the skew is removed.

    @Test
    @Order(3)
    @DisplayName("3.1 clock-drift-forward-2s")
    void scenario_3_1_clockDriftForward2s() {
        // Steady state
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("9000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("9000.00"), Duration.ofSeconds(10));
        }

        // Drift a follower's clock forward by 2 seconds (app-level skew,
        // NOT the container's OS clock — see AdjustableClock)
        int driftIdx = (leaderIdx + 1) % 3;
        client.setClockOffset(driftIdx, 2000);

        long before = client.getHlcStatus(driftIdx).path("physicalTime").asLong();

        // Cause the drifted node's HLC to actually tick, via replication
        client.deposit(account, new BigDecimal("100.00"), UUID.randomUUID());
        client.waitForBalance(driftIdx, account, new BigDecimal("9100.00"), Duration.ofSeconds(10));

        JsonNode driftedStatus = client.getHlcStatus(driftIdx);
        long after = driftedStatus.path("physicalTime").asLong();
        long reportedDrift = driftedStatus.path("drift").asLong();

        assertThat(after)
                .as("HLC physical time must not go backward while drifting")
                .isGreaterThanOrEqualTo(before);
        assertThat(reportedDrift)
                .as("Reported HLC drift should reflect the ~2s forward skew")
                .isBetween(1000L, 4000L);

        // Other nodes must still agree — drift on one node must not
        // corrupt replication or ordering
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account, new BigDecimal("9100.00"), Duration.ofSeconds(10));
        }

        // Heal: remove the clock skew
        client.setClockOffset(driftIdx, 0);
        sleep(1000);

        long afterHeal = client.getHlcStatus(driftIdx).path("physicalTime").asLong();
        assertThat(afterHeal)
                .as("HLC must remain monotonic after clock skew is removed")
                .isGreaterThanOrEqualTo(after);

        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[3.1] clock-drift-forward-2s PASSED.");
    }

    // Scenario 5.3 — Poison Pill Message (STUBBED — needs Kafka)
    //
    // Status: Kafka is not yet wired into ChaosLedger. Per the Week 12
    //   guide's own guidance, this scenario is stubbed with a clear
    //   reason rather than silently dropped. CHAOS_CATALOG.md records
    //   it as "not yet implemented," not "passing."

    @Test
    @Order(4)
    @Disabled("Requires Kafka, which is not wired into ChaosLedger yet — "
            + "stubbed per Week 12 guidance. Revisit once event publishing after "
            + "Raft commit is implemented.")
    @DisplayName("5.3 poison-pill-message (stub — needs Kafka)")
    void scenario_5_3_poisonPillMessage_stub() {
        // Intentionally empty. See class javadoc above and CHAOS_CATALOG.md.
    }

    // Scenario 7.1 — Concurrent Transfer, Same Source Account
    //
    // Hypothesis: Two concurrent transfers withdrawing from the SAME
    //   account race against each other. OCC (the unique
    //   (aggregate_id, version) constraint from Week 2) ensures exactly
    //   one racing writer wins; the loser is rejected, not double-applied.
    //
    // Failure mode: Application-level race — no network chaos involved.

    @Test
    @Order(5)
    @DisplayName("7.1 concurrent-transfer-same-account")
    void scenario_7_1_concurrentTransferSameAccount() throws Exception {
        // Steady state
        UUID source = client.openAccount(UUID.randomUUID(), "INR");
        UUID destA = client.openAccount(UUID.randomUUID(), "INR");
        UUID destB = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(source, new BigDecimal("1000.00"), UUID.randomUUID());

        client.findLeader();
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, source, new BigDecimal("1000.00"), Duration.ofSeconds(10));
        }

        // ── Inject: two concurrent transfers of 700 each from the same
        //    1000-balance account. At most one can legally succeed. ──
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        Callable<Void> transferA = () -> {
            startGate.await();
            try {
                client.transfer(source, destA, new BigDecimal("700.00"), UUID.randomUUID());
                succeeded.incrementAndGet();
            } catch (Exception e) {
                rejected.incrementAndGet();
            }
            return null;
        };
        Callable<Void> transferB = () -> {
            startGate.await();
            try {
                client.transfer(source, destB, new BigDecimal("700.00"), UUID.randomUUID());
                succeeded.incrementAndGet();
            } catch (Exception e) {
                rejected.incrementAndGet();
            }
            return null;
        };

        Future<Void> fa = pool.submit(transferA);
        Future<Void> fb = pool.submit(transferB);
        startGate.countDown();
        fa.get(30, TimeUnit.SECONDS);
        fb.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Verify hypothesis: exactly one transfer wins
        assertThat(succeeded.get())
                .as("Exactly one of the two racing 700-transfers should succeed")
                .isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, source, new BigDecimal("300.00"), Duration.ofSeconds(10));
        }

        // Conservation of money on every node
        for (int i = 0; i < 3; i++) {
            BigDecimal total = client.getBalance(i, source)
                    .add(client.getBalance(i, destA))
                    .add(client.getBalance(i, destB));
            assertThat(total)
                    .as("Conservation of money on node " + i)
                    .isEqualByComparingTo(new BigDecimal("1000.00"));
        }

        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[7.1] concurrent-transfer-same-account PASSED. "
                + "1 succeeded, 1 correctly rejected.");
    }
}
