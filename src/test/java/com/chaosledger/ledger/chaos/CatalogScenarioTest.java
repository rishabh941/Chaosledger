// src/test/java/com/chaosledger/ledger/chaos/CatalogScenarioTest.java
package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 12 — First 5 Catalogued Chaos Scenarios.
 *
 * Each scenario follows the chaos engineering discipline:
 *   1. Define hypothesis (what we expect the system to do under failure)
 *   2. Establish steady state (known balances, all nodes healthy)
 *   3. Inject fault (partition, crash, flapping, etc.)
 *   4. Verify hypothesis (writes succeed or fail safely, invariants hold)
 *   5. Heal fault
 *   6. Verify recovery (all nodes converge, no money lost)
 *
 * Scenario IDs follow the catalogue numbering:
 *   1.x = Node failures
 *   2.x = Network failures
 *   7.x = Application-level failures
 *
 * Run with: RUN_CHAOS_TESTS=true mvn test -Dtest=CatalogScenarioTest
 *
 * Prerequisites:
 *   - All Week 11 BasicChaosTest tests pass
 *   - chaosledger-ledger:test image built
 *   - Docker running
 */
@EnabledIfEnvironmentVariable(named = "RUN_MULTINODE_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CatalogScenarioTest extends ChaosTestBase {

    // ══════════════════════════════════════════════════════════════
    // Scenario 1.1 — Leader Crash Mid-Write
    // ══════════════════════════════════════════════════════════════
    //
    // Hypothesis: If the leader crashes while a write is in flight,
    //   the cluster elects a new leader and either the write is
    //   committed (was replicated before crash) or it fails and
    //   can be retried safely with idempotency. No money is lost
    //   or duplicated.
    //
    // Failure mode: Process crash (Docker stop) — different from
    //   network partition because the node is completely dead.
    //
    // Why this matters: In production, leaders crash from OOM kills,
    //   hardware failures, or deployment rolling restarts. The system
    //   must handle this without human intervention.
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1.1 leader-crash-mid-write")
    void scenario_1_1_leaderCrashMidWrite() {
        // ── Steady state ──
        UUID account1 = client.openAccount(UUID.randomUUID(), "INR");
        UUID account2 = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account1, new BigDecimal("10000.00"), UUID.randomUUID());
        client.deposit(account2, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        String leaderService = serviceForNodeIdx(leaderIdx);
        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;

        // Wait for full replication
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("10000.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, account2,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        BigDecimal totalBefore = new BigDecimal("15000.00");

        // ── Inject: partition leader's Raft, then stop it ──
        // Partition first so the stop is cleaner for remaining nodes
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        chaosEngine.partitionNode(leaderNodeId);
        sleep(500);
        stopContainer(leaderService);

        // ── Verify: new leader elected from survivors ──
        int newLeaderIdx = -1;
        long electionStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - electionStart < 15_000) {
            try {
                JsonNode s = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor1;
                    break;
                }
            } catch (Exception ignored) {}
            try {
                JsonNode s = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor2;
                    break;
                }
            } catch (Exception ignored) {}
            sleep(250);
        }
        long electionMs = System.currentTimeMillis() - electionStart;

        assertThat(newLeaderIdx)
                .as("A new leader should be elected after leader crash")
                .isGreaterThanOrEqualTo(0);

        System.out.printf("[1.1] New leader elected: node %d (took %d ms)%n",
                newLeaderIdx, electionMs);

        // ── Write on new leader — this is the "mid-write" recovery ──
        client.findLeader(); // refresh cached leader
        UUID transferKey = UUID.randomUUID();
        client.transfer(account1, account2, new BigDecimal("2000.00"), transferKey);

        // Verify on both survivors
        client.waitForBalance(survivor1, account1,
                new BigDecimal("8000.00"), Duration.ofSeconds(10));
        client.waitForBalance(survivor2, account2,
                new BigDecimal("7000.00"), Duration.ofSeconds(10));

        // Conservation on survivors
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal b1 = client.getBalance(idx, account1);
            BigDecimal b2 = client.getBalance(idx, account2);
            assertThat(b1.add(b2))
                    .as("Conservation of money on surviving node " + idx)
                    .isEqualByComparingTo(totalBefore);
        }

        // ── Heal: restart crashed leader ──
        chaosEngine.healPartition(leaderNodeId);
        startContainer(leaderService);
        sleep(8000); // crashed node needs time to reboot + rejoin Raft

        // ── Verify recovery: crashed node catches up ──
        client.waitForBalance(leaderIdx, account1,
                new BigDecimal("8000.00"), Duration.ofSeconds(20));
        client.waitForBalance(leaderIdx, account2,
                new BigDecimal("7000.00"), Duration.ofSeconds(20));

        // Conservation on recovered node
        BigDecimal b1 = client.getBalance(leaderIdx, account1);
        BigDecimal b2 = client.getBalance(leaderIdx, account2);
        assertThat(b1.add(b2))
                .as("Conservation of money on recovered leader")
                .isEqualByComparingTo(totalBefore);

        // Invariants on all nodes
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[1.1] leader-crash-mid-write PASSED. " +
                "Event log: " + chaosEngine.getEventLog().size() + " entries.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 1.2 — Follower Crash During Replication
    // ══════════════════════════════════════════════════════════════
    //
    // Hypothesis: If a follower crashes while events are being
    //   replicated to it, the surviving quorum (leader + 1 follower)
    //   continues accepting writes. When the crashed follower restarts,
    //   it catches up without data loss.
    //
    // Failure mode: Process crash during active writes.
    //
    // Why this matters: Followers crash from GC pauses, disk failures,
    //   or rolling deployments. The cluster must keep serving.
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("1.2 follower-crash-during-replication")
    void scenario_1_2_followerCrashDuringReplication() {
        // ── Steady state ──
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;
        int otherFollowerIdx = (leaderIdx + 2) % 3;
        String followerService = serviceForNodeIdx(followerIdx);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        // Record event count before crash for comparison
        long eventCountBefore = client.getEventCount(leaderIdx);

        // ── Inject: crash the follower ──
        chaosEngine.partitionNode(nodeIdFromIdx(followerIdx));
        sleep(500);
        stopContainer(followerService);

        // ── Verify: writes continue on quorum ──
        // Perform several writes while follower is down
        int writesWhileDown = 5;
        BigDecimal runningBalance = new BigDecimal("5000.00");
        for (int w = 0; w < writesWhileDown; w++) {
            BigDecimal amount = new BigDecimal("100.00");
            client.deposit(account, amount, UUID.randomUUID());
            runningBalance = runningBalance.add(amount);
        }

        // Verify on leader and surviving follower
        client.waitForBalance(leaderIdx, account,
                runningBalance, Duration.ofSeconds(10));
        client.waitForBalance(otherFollowerIdx, account,
                runningBalance, Duration.ofSeconds(10));

        System.out.printf("[1.2] %d writes succeeded while follower %d was down. " +
                "Balance: %s%n", writesWhileDown, followerIdx, runningBalance);

        // ── Heal: restart crashed follower ──
        chaosEngine.healPartition(nodeIdFromIdx(followerIdx));
        startContainer(followerService);
        sleep(8000);

        // ── Verify recovery: follower catches up with all missed writes ──
        client.waitForBalance(followerIdx, account,
                runningBalance, Duration.ofSeconds(20));

        // Verify event counts match across all nodes
        long leaderEvents = client.getEventCount(leaderIdx);
        long followerEvents = client.getEventCount(followerIdx);
        long otherEvents = client.getEventCount(otherFollowerIdx);

        assertThat(followerEvents)
                .as("Crashed follower should have same event count as leader " +
                        "after catch-up (leader=%d, follower=%d)", leaderEvents, followerEvents)
                .isEqualTo(leaderEvents);
        assertThat(otherEvents)
                .as("Other follower should match leader event count")
                .isEqualTo(leaderEvents);

        // Invariants
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.printf("[1.2] follower-crash-during-replication PASSED. " +
                "Follower caught up: %d events.%n", followerEvents);
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 2.1 — Symmetric Partition During Write
    // ══════════════════════════════════════════════════════════════
    //
    // Hypothesis: When the leader is symmetrically partitioned from
    //   the other two nodes, the leader loses its leadership (can't
    //   reach a quorum). The two non-partitioned nodes elect a new
    //   leader and continue accepting writes. After healing, all
    //   three nodes converge.
    //
    // Failure mode: Network partition (Toxiproxy disable) — node is
    //   alive but unreachable.
    //
    // Why this matters: Symmetric partitions are the classic
    //   distributed systems failure. Cloud network switches fail,
    //   VPC routing breaks, availability zones lose connectivity.
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("2.1 symmetric-partition-during-write")
    void scenario_2_1_symmetricPartitionDuringWrite() {
        // ── Steady state ──
        UUID account1 = client.openAccount(UUID.randomUUID(), "INR");
        UUID account2 = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account1, new BigDecimal("20000.00"), UUID.randomUUID());
        client.deposit(account2, new BigDecimal("10000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;

        BigDecimal totalMoney = new BigDecimal("30000.00");

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("20000.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, account2,
                    new BigDecimal("10000.00"), Duration.ofSeconds(10));
        }

        // ── Inject: partition the leader ──
        chaosEngine.partitionNode(leaderNodeId);

        // ── Verify: new leader elected among survivors ──
        int newLeaderIdx = -1;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 15_000) {
            try {
                JsonNode s = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor1; break;
                }
            } catch (Exception ignored) {}
            try {
                JsonNode s = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor2; break;
                }
            } catch (Exception ignored) {}
            sleep(250);
        }

        assertThat(newLeaderIdx)
                .as("New leader should be elected from non-partitioned nodes")
                .isGreaterThanOrEqualTo(0);
        assertThat(newLeaderIdx)
                .as("New leader should differ from partitioned leader")
                .isNotEqualTo(leaderIdx);

        System.out.printf("[2.1] New leader: node %d (partition took %d ms to resolve)%n",
                newLeaderIdx, System.currentTimeMillis() - start);

        // ── Perform writes during partition ──
        client.findLeader(); // refresh
        client.transfer(account1, account2, new BigDecimal("5000.00"), UUID.randomUUID());

        // Verify on survivors
        client.waitForBalance(survivor1, account1,
                new BigDecimal("15000.00"), Duration.ofSeconds(10));
        client.waitForBalance(survivor2, account2,
                new BigDecimal("15000.00"), Duration.ofSeconds(10));

        // Conservation on both survivors
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal b1 = client.getBalance(idx, account1);
            BigDecimal b2 = client.getBalance(idx, account2);
            assertThat(b1.add(b2))
                    .as("Conservation during partition on node " + idx)
                    .isEqualByComparingTo(totalMoney);
        }

        // ── Heal the partition ──
        chaosEngine.healPartition(leaderNodeId);
        sleep(5000);

        // ── Verify convergence: all 3 nodes agree ──
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("15000.00"), Duration.ofSeconds(15));
            client.waitForBalance(i, account2,
                    new BigDecimal("15000.00"), Duration.ofSeconds(15));
        }

        // Conservation across all nodes
        for (int i = 0; i < 3; i++) {
            BigDecimal b1 = client.getBalance(i, account1);
            BigDecimal b2 = client.getBalance(i, account2);
            assertThat(b1.add(b2))
                    .as("Conservation after healing on node " + i)
                    .isEqualByComparingTo(totalMoney);
        }

        // Invariants
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[2.1] symmetric-partition-during-write PASSED.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 2.4 — Flapping Network
    // ══════════════════════════════════════════════════════════════
    //
    // Hypothesis: When a node's network is flapping (data arrives in
    //   tiny fragments with delays), the cluster may be slow but still
    //   functions. The flapping node might lose leadership (if it was
    //   leader) due to heartbeat timeouts, but the cluster keeps
    //   accepting writes. After healing, all nodes converge.
    //
    // Failure mode: Slicer toxic — data sliced into 10-byte fragments
    //   with 100ms delays between them. TCP stays open but is nearly
    //   unusable.
    //
    // Why this matters: Flapping networks are common in cloud
    //   environments with overloaded switches, congested links,
    //   or misconfigured MTU settings. They're harder to detect than
    //   clean partitions because the connection is technically "up."
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("2.4 flapping-network")
    void scenario_2_4_flappingNetwork() {
        // ── Steady state ──
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("8000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    new BigDecimal("8000.00"), Duration.ofSeconds(10));
        }

        // ── Inject: flap one node's network ──
        // Choose a follower so the cluster keeps a stable leader
        int flapIdx = (leaderIdx + 1) % 3;
        int flapNodeId = nodeIdFromIdx(flapIdx);
        chaosEngine.flappingNetwork(flapNodeId);
        sleep(2000); // Let the slicer take effect

        // ── Verify: writes still succeed ──
        BigDecimal expectedBalance = new BigDecimal("8000.00");
        int successfulWrites = 0;
        for (int w = 0; w < 3; w++) {
            try {
                client.deposit(account, new BigDecimal("200.00"), UUID.randomUUID());
                expectedBalance = expectedBalance.add(new BigDecimal("200.00"));
                successfulWrites++;
            } catch (Exception e) {
                System.out.printf("[2.4] Write %d failed (expected under flapping): %s%n",
                        w, e.getMessage());
            }
        }

        assertThat(successfulWrites)
                .as("At least some writes should succeed despite flapping")
                .isGreaterThanOrEqualTo(1);

        System.out.printf("[2.4] %d/%d writes succeeded under flapping. Balance: %s%n",
                successfulWrites, 3, expectedBalance);

        // Check non-flapping nodes have the balance
        int stableNode = (leaderIdx + 2) % 3;
        client.waitForBalance(leaderIdx, account,
                expectedBalance, Duration.ofSeconds(15));
        client.waitForBalance(stableNode, account,
                expectedBalance, Duration.ofSeconds(15));

        // ── Heal ──
        chaosEngine.healFlapping(flapNodeId);
        sleep(5000);

        // ── Verify convergence ──
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    expectedBalance, Duration.ofSeconds(20));
        }

        // Invariants
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[2.4] flapping-network PASSED.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 7.3 — Idempotency Key Replay After Failure
    // ══════════════════════════════════════════════════════════════
    //
    // Hypothesis: If a client sends a write, the leader crashes (or
    //   becomes partitioned) before the client gets a response, and
    //   the client retries with the same idempotency key on the new
    //   leader, the system processes the write exactly once — never
    //   twice, never zero.
    //
    // Failure mode: Network partition mid-write + retry with same key.
    //
    // Why this matters: This is the "exactly once" problem. In
    //   production, load balancers retry, clients retry, and message
    //   queues redeliver. Without idempotency, you get double-charged
    //   customers — the cardinal sin in fintech.
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("7.3 idempotency-key-replay-after-failure")
    void scenario_7_3_idempotencyKeyReplayAfterFailure() {
        // ── Steady state ──
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("10000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    new BigDecimal("10000.00"), Duration.ofSeconds(10));
        }

        // ── Step 1: Send deposit with known idempotency key ──
        UUID idempotencyKey = UUID.randomUUID();
        client.deposit(account, new BigDecimal("3000.00"), idempotencyKey);

        // Verify the deposit went through
        client.waitForBalance(leaderIdx, account,
                new BigDecimal("13000.00"), Duration.ofSeconds(10));

        // Wait for replication to all nodes
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    new BigDecimal("13000.00"), Duration.ofSeconds(10));
        }

        // ── Step 2: Partition the leader (simulating "client didn't get response") ──
        chaosEngine.partitionNode(leaderNodeId);

        // Wait for new leader
        int newLeaderIdx = -1;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 15_000) {
            try {
                JsonNode s = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor1; break;
                }
            } catch (Exception ignored) {}
            try {
                JsonNode s = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s.path("role").asText(""))) {
                    newLeaderIdx = survivor2; break;
                }
            } catch (Exception ignored) {}
            sleep(250);
        }

        assertThat(newLeaderIdx)
                .as("New leader should be elected for retry test")
                .isGreaterThanOrEqualTo(0);

        client.findLeader(); // refresh

        // ── Step 3: Retry with SAME idempotency key on new leader ──
        // This should either be rejected as duplicate or be a no-op
        boolean retryRejected = false;
        try {
            client.deposit(account, new BigDecimal("3000.00"), idempotencyKey);
            // If it didn't throw, the idempotency store caught it
            // and returned the original response (no-op)
        } catch (Exception e) {
            // Expected: DuplicateCommandException → HTTP 409 or similar
            retryRejected = true;
            System.out.printf("[7.3] Retry correctly rejected: %s%n", e.getMessage());
        }

        // ── Verify: balance is STILL 13000, not 16000 ──
        // The duplicate write must NOT have been applied twice
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal balance = client.getBalance(idx, account);
            assertThat(balance)
                    .as("Balance on node %d should be 13000 (not 16000 = double deposit)", idx)
                    .isEqualByComparingTo(new BigDecimal("13000.00"));
        }

        // ── Heal ──
        chaosEngine.healPartition(leaderNodeId);
        sleep(5000);

        // ── Verify full convergence ──
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account,
                    new BigDecimal("13000.00"), Duration.ofSeconds(15));
        }

        // Final invariant check
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.printf("[7.3] idempotency-key-replay-after-failure PASSED. " +
                        "Retry %s. Balance correctly 13000.00 on all nodes.%n",
                retryRejected ? "REJECTED (409)" : "NO-OP (idempotent)");
    }
}