// src/test/java/com/chaosledger/ledger/chaos/CatalogScenarioManualTest.java
package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_CHAOS_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CatalogScenarioManualTest extends ManualChaosTestBase {

    // ── Resilient helpers ───────────────────────────────────────

    /**
     * Like client.waitForBalance but tolerates connection errors
     * (EOF, timeout, refused) that happen when a node is restarting
     * or a proxy is re-enabling.
     */
    private void waitForBalanceResilient(int nodeIdx, UUID accountId,
                                         BigDecimal expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Exception lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                BigDecimal actual = client.getBalance(nodeIdx, accountId);
                if (actual.compareTo(expected) == 0) return;
                lastError = null; // connected fine, just wrong balance
            } catch (Exception e) {
                lastError = e; // node might be restarting
            }
            sleep(500);
        }
        // Final attempt — let it throw
        if (lastError != null) {
            throw new AssertionError(
                    "Node " + nodeIdx + " never became reachable within " +
                            timeout.toSeconds() + "s. Last error: " + lastError.getMessage());
        }
        BigDecimal actual = client.getBalance(nodeIdx, accountId);
        assertThat(actual)
                .as("Balance on node " + nodeIdx)
                .isEqualByComparingTo(expected);
    }

    /**
     * Find a new leader from specific survivor nodes, tolerating
     * connection errors during election.
     */
    private int waitForNewLeader(int excludeIdx, long timeoutMs) {
        int survivor1 = (excludeIdx + 1) % 3;
        int survivor2 = (excludeIdx + 2) % 3;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                JsonNode s = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s.path("role").asText(""))) return survivor1;
            } catch (Exception ignored) {}
            try {
                JsonNode s = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s.path("role").asText(""))) return survivor2;
            } catch (Exception ignored) {}
            sleep(250);
        }
        return -1;
    }

    /**
     * Get balance safely — returns null if node is unreachable.
     */
    private BigDecimal getBalanceSafe(int nodeIdx, UUID accountId) {
        try {
            return client.getBalance(nodeIdx, accountId);
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 1.1 — Leader Crash Mid-Write
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
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;
        BigDecimal totalBefore = new BigDecimal("15000.00");

        // Wait for full replication
        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account1,
                    new BigDecimal("10000.00"), Duration.ofSeconds(15));
            waitForBalanceResilient(i, account2,
                    new BigDecimal("5000.00"), Duration.ofSeconds(15));
        }

        // ── Inject: partition + crash leader ──
        chaosEngine.partitionNode(leaderNodeId);
        sleep(500);
        stopContainer(leaderService);

        // ── Verify: new leader elected ──
        int newLeaderIdx = waitForNewLeader(leaderIdx, 20_000);
        assertThat(newLeaderIdx)
                .as("New leader should be elected after leader crash")
                .isGreaterThanOrEqualTo(0);
        System.out.printf("[1.1] New leader: node %d%n", newLeaderIdx);

        // ── Write on new leader ──
        client.findLeader();
        client.transfer(account1, account2, new BigDecimal("2000.00"), UUID.randomUUID());

        // Verify on survivors only (crashed node is down)
        waitForBalanceResilient(survivor1, account1,
                new BigDecimal("8000.00"), Duration.ofSeconds(15));
        waitForBalanceResilient(survivor2, account2,
                new BigDecimal("7000.00"), Duration.ofSeconds(15));

        // Conservation on survivors
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal b1 = client.getBalance(idx, account1);
            BigDecimal b2 = client.getBalance(idx, account2);
            assertThat(b1.add(b2))
                    .as("Conservation on surviving node " + idx)
                    .isEqualByComparingTo(totalBefore);
        }

        // ── Heal: restart crashed leader ──
        chaosEngine.healPartition(leaderNodeId);
        startContainer(leaderService);
        sleep(20000); // Windows containers take longer to restart

        // ── Verify recovery ──
        waitForBalanceResilient(leaderIdx, account1,
                new BigDecimal("8000.00"), Duration.ofSeconds(45));
        waitForBalanceResilient(leaderIdx, account2,
                new BigDecimal("7000.00"), Duration.ofSeconds(45));

        BigDecimal b1 = client.getBalance(leaderIdx, account1);
        BigDecimal b2 = client.getBalance(leaderIdx, account2);
        assertThat(b1.add(b2))
                .as("Conservation on recovered leader")
                .isEqualByComparingTo(totalBefore);

        System.out.println("[1.1] leader-crash-mid-write PASSED.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 1.2 — Follower Crash During Replication
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("1.2 follower-crash-during-replication")
    void scenario_1_2_followerCrashDuringReplication() {
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;
        int otherFollowerIdx = (leaderIdx + 2) % 3;
        String followerService = serviceForNodeIdx(followerIdx);

        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    new BigDecimal("5000.00"), Duration.ofSeconds(15));
        }

        // ── Inject: crash follower ──
        chaosEngine.partitionNode(nodeIdFromIdx(followerIdx));
        sleep(500);
        stopContainer(followerService);

        // ── Verify: writes continue ──
        BigDecimal runningBalance = new BigDecimal("5000.00");
        for (int w = 0; w < 5; w++) {
            client.deposit(account, new BigDecimal("100.00"), UUID.randomUUID());
            runningBalance = runningBalance.add(new BigDecimal("100.00"));
        }

        // Check on leader and surviving follower only
        waitForBalanceResilient(leaderIdx, account,
                runningBalance, Duration.ofSeconds(10));
        waitForBalanceResilient(otherFollowerIdx, account,
                runningBalance, Duration.ofSeconds(10));

        System.out.printf("[1.2] 5 writes succeeded while follower %d was down.%n", followerIdx);

        // ── Heal: restart follower ──
        chaosEngine.healPartition(nodeIdFromIdx(followerIdx));
        startContainer(followerService);
        sleep(12000);

        // ── Verify recovery ──
        waitForBalanceResilient(followerIdx, account,
                runningBalance, Duration.ofSeconds(30));

        // Compare event counts (tolerate connection flakiness)
        long leaderEvents = client.getEventCount(leaderIdx);
        long followerEvents = client.getEventCount(followerIdx);
        assertThat(followerEvents)
                .as("Follower event count should match leader after catch-up")
                .isEqualTo(leaderEvents);

        System.out.printf("[1.2] follower-crash-during-replication PASSED. Events: %d%n",
                followerEvents);
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 2.1 — Symmetric Partition During Write
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("2.1 symmetric-partition-during-write")
    void scenario_2_1_symmetricPartitionDuringWrite() {
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
            waitForBalanceResilient(i, account1,
                    new BigDecimal("20000.00"), Duration.ofSeconds(15));
            waitForBalanceResilient(i, account2,
                    new BigDecimal("10000.00"), Duration.ofSeconds(15));
        }

        // ── Inject: TRUE symmetric partition ──
        partitionNodeSymmetric(leaderNodeId);

        // ── Verify: new leader elected among survivors ──
        int newLeaderIdx = waitForNewLeader(leaderIdx, 30_000);
        assertThat(newLeaderIdx)
                .as("New leader should be elected from non-partitioned nodes")
                .isGreaterThanOrEqualTo(0);

        client.findLeader();
        client.transfer(account1, account2, new BigDecimal("5000.00"), UUID.randomUUID());

        waitForBalanceResilient(survivor1, account1,
                new BigDecimal("15000.00"), Duration.ofSeconds(10));
        waitForBalanceResilient(survivor2, account2,
                new BigDecimal("15000.00"), Duration.ofSeconds(10));

        // ── Heal ──
        chaosEngine.healPartition(leaderNodeId);
        sleep(5000);

        // ── Verify convergence ──
        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account1,
                    new BigDecimal("15000.00"), Duration.ofSeconds(20));
            waitForBalanceResilient(i, account2,
                    new BigDecimal("15000.00"), Duration.ofSeconds(20));
            BigDecimal b1 = client.getBalance(i, account1);
            BigDecimal b2 = client.getBalance(i, account2);
            assertThat(b1.add(b2))
                    .as("Conservation on node " + i)
                    .isEqualByComparingTo(totalMoney);
        }

        System.out.println("[2.1] symmetric-partition-during-write PASSED.");
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 2.4 — Flapping Network
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("2.4 flapping-network")
    void scenario_2_4_flappingNetwork() {
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("8000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    new BigDecimal("8000.00"), Duration.ofSeconds(15));
        }

        // Flap a follower (not the leader)
        int flapIdx = (leaderIdx + 1) % 3;
        chaosEngine.flappingNetwork(nodeIdFromIdx(flapIdx));
        sleep(2000);

        BigDecimal expectedBalance = new BigDecimal("8000.00");
        int successfulWrites = 0;
        for (int w = 0; w < 3; w++) {
            try {
                client.deposit(account, new BigDecimal("200.00"), UUID.randomUUID());
                expectedBalance = expectedBalance.add(new BigDecimal("200.00"));
                successfulWrites++;
            } catch (Exception e) {
                System.out.printf("[2.4] Write %d failed: %s%n", w, e.getMessage());
            }
        }

        assertThat(successfulWrites).isGreaterThanOrEqualTo(1);

        // ── Heal ──
        chaosEngine.healFlapping(nodeIdFromIdx(flapIdx));
        sleep(5000);

        // ── Verify convergence ──
        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    expectedBalance, Duration.ofSeconds(20));
        }

        System.out.printf("[2.4] flapping-network PASSED. %d/3 writes.%n", successfulWrites);
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario 7.3 — Idempotency Key Replay After Failure
    // ══════════════════════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("7.3 idempotency-key-replay-after-failure")
    void scenario_7_3_idempotencyKeyReplayAfterFailure() {
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("10000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int leaderNodeId = nodeIdFromIdx(leaderIdx);
        int survivor1 = (leaderIdx + 1) % 3;
        int survivor2 = (leaderIdx + 2) % 3;

        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    new BigDecimal("10000.00"), Duration.ofSeconds(15));
        }

        // Step 1: deposit with known key
        UUID idempotencyKey = UUID.randomUUID();
        client.deposit(account, new BigDecimal("3000.00"), idempotencyKey);

        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    new BigDecimal("13000.00"), Duration.ofSeconds(15));
        }

        // Step 2: TRUE symmetric partition of leader
        partitionNodeSymmetric(leaderNodeId);

        int newLeaderIdx = waitForNewLeader(leaderIdx, 30_000);
        assertThat(newLeaderIdx)
                .as("New leader should be elected for retry test")
                .isGreaterThanOrEqualTo(0);
        client.findLeader();

        // Step 3: retry with SAME key
        try {
            client.deposit(account, new BigDecimal("3000.00"), idempotencyKey);
        } catch (Exception e) {
            System.out.printf("[7.3] Retry rejected: %s%n", e.getMessage());
        }

        // Balance must be 13000, NOT 16000
        for (int idx : new int[]{survivor1, survivor2}) {
            BigDecimal balance = client.getBalance(idx, account);
            assertThat(balance)
                    .as("Balance on node %d should be 13000 not 16000", idx)
                    .isEqualByComparingTo(new BigDecimal("13000.00"));
        }

        // ── Heal ──
        chaosEngine.healPartition(leaderNodeId);
        sleep(5000);

        for (int i = 0; i < 3; i++) {
            waitForBalanceResilient(i, account,
                    new BigDecimal("13000.00"), Duration.ofSeconds(15));
        }

        System.out.println("[7.3] idempotency-key-replay PASSED.");
    }
}