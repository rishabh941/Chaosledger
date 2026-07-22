// src/test/java/com/chaosledger/ledger/chaos/BasicChaosTest.java
package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 11 — First chaos tests.
 *
 * These tests verify that the ChaosEngine + Toxiproxy infrastructure
 * works correctly and that the cluster survives basic failure injection.
 * They are the foundation for the 10 catalogued scenarios in Week 12–13.
 *
 * Each test follows the chaos engineering discipline:
 *   1. Establish steady state (healthy cluster, known balances)
 *   2. Hypothesize: "the system will continue to work despite X"
 *   3. Inject fault X
 *   4. Verify the hypothesis (balances correct, invariants pass)
 *   5. Heal the fault
 *   6. Verify recovery (partitioned node catches up)
 *
 * Run with: RUN_CHAOS_TESTS=true mvn test -Dtest=BasicChaosTest
 */

@EnabledIfEnvironmentVariable(named = "RUN_MULTINODE_TESTS", matches = "true")
public class BasicChaosTest extends ChaosTestBase {

    // ── Test 1: Partition a follower ────────────────────────────

    @Test
    @Order(1)
    @DisplayName("partitionFollower_clusterContinuesToWork")
    void partitionFollower_clusterContinuesToWork() {
        // 1. Steady state: create account, deposit, verify
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("1000.00"), UUID.randomUUID());

        // Identify leader and a follower
        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;
        int followerNodeId = nodeIdFromIdx(followerIdx);

        // Wait for replication to all nodes
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("1000.00"), Duration.ofSeconds(10));
        }

        // 2. Inject: partition the follower
        chaosEngine.partitionNode(followerNodeId);
        sleep(2000); // Let the partition take effect

        // 3. Verify: cluster still works — deposit should succeed
        //    (leader + remaining follower = quorum of 2)
        client.deposit(accountId, new BigDecimal("500.00"), UUID.randomUUID());

        // Verify on leader
        client.waitForBalance(leaderIdx, accountId,
                new BigDecimal("1500.00"), Duration.ofSeconds(10));

        // 4. Heal: restore the partitioned follower
        chaosEngine.healPartition(followerNodeId);
        sleep(3000); // Give time for catch-up

        // 5. Verify recovery: partitioned follower catches up
        client.waitForBalance(followerIdx, accountId,
                new BigDecimal("1500.00"), Duration.ofSeconds(15));

        // 6. Invariants pass on all nodes
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[BasicChaosTest] partitionFollower PASSED. " +
                "Event log: " + chaosEngine.getEventLog());
    }

    // ── Test 2: Partition the leader ────────────────────────────

    @Test
    @Order(2)
    @DisplayName("partitionLeader_newLeaderElected_writesSucceed")
    void partitionLeader_newLeaderElected_writesSucceed() {
        // 1. Steady state
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("2000.00"), UUID.randomUUID());

        int oldLeaderIdx = client.findLeader();
        int oldLeaderNodeId = nodeIdFromIdx(oldLeaderIdx);

        // Wait for full replication
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("2000.00"), Duration.ofSeconds(10));
        }

        // 2. Inject: partition the leader
        chaosEngine.partitionNode(oldLeaderNodeId);

        // 3. Verify: new leader elected within 5 seconds
        sleep(1000); // Give Raft a moment to detect the loss

        // Find a surviving node to check
        int survivor1 = (oldLeaderIdx + 1) % 3;
        int survivor2 = (oldLeaderIdx + 2) % 3;

        long electionStart = System.currentTimeMillis();
        int newLeaderIdx = -1;
        while (System.currentTimeMillis() - electionStart < 10_000) {
            try {
                JsonNode s1 = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s1.path("role").asText(""))) {
                    newLeaderIdx = survivor1;
                    break;
                }
            } catch (Exception ignored) { }
            try {
                JsonNode s2 = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s2.path("role").asText(""))) {
                    newLeaderIdx = survivor2;
                    break;
                }
            } catch (Exception ignored) { }
            sleep(250);
        }
        long electionTime = System.currentTimeMillis() - electionStart;

        assertThat(newLeaderIdx)
                .as("A new leader should be elected")
                .isGreaterThanOrEqualTo(0);
        assertThat(newLeaderIdx)
                .as("New leader should be different from old leader")
                .isNotEqualTo(oldLeaderIdx);
        assertThat(electionTime)
                .as("Election should complete under 10 seconds")
                .isLessThan(10_000);

        System.out.printf("[BasicChaosTest] New leader elected: node %d (took %d ms)%n",
                newLeaderIdx, electionTime);

        // 4. Write to the new leader
        // Force client to rediscover leader
        client.findLeader();
        client.deposit(accountId, new BigDecimal("300.00"), UUID.randomUUID());

        // Verify on both survivors
        client.waitForBalance(survivor1, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(10));
        client.waitForBalance(survivor2, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(10));

        // 5. Heal: restore old leader
        chaosEngine.healPartition(oldLeaderNodeId);
        sleep(5000); // Old leader needs time to rejoin and catch up

        // 6. Verify: old leader catches up
        client.waitForBalance(oldLeaderIdx, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(15));

        // Invariants on all nodes
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[BasicChaosTest] partitionLeader PASSED.");
    }

    // ── Test 3: Slow network ────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("slowNetwork_clusterStillFunctions")
    void slowNetwork_clusterStillFunctions() {
        // 1. Steady state
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        // 2. Inject: 500ms latency on ALL nodes
        for (int nodeId = 1; nodeId <= 3; nodeId++) {
            chaosEngine.slowNetwork(nodeId, 200, 50);
        }
        sleep(1000);

        // 3. Verify: writes still succeed (just slower)
        long writeStart = System.currentTimeMillis();
        client.deposit(accountId, new BigDecimal("100.00"), UUID.randomUUID());
        long writeTime = System.currentTimeMillis() - writeStart;

        System.out.printf("[BasicChaosTest] Write under latency took %d ms%n", writeTime);

        // Balance should eventually reach all nodes
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5100.00"), Duration.ofSeconds(30));
        }

        // 4. Heal
        chaosEngine.healAll();
        sleep(1000);

        // 5. Verify recovery: fast writes again
        long fastWriteStart = System.currentTimeMillis();
        client.deposit(accountId, new BigDecimal("50.00"), UUID.randomUUID());
        long fastWriteTime = System.currentTimeMillis() - fastWriteStart;

        System.out.printf("[BasicChaosTest] Write after healing took %d ms%n", fastWriteTime);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5150.00"), Duration.ofSeconds(10));
        }

        // Invariants
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[BasicChaosTest] slowNetwork PASSED.");
    }

    // ── Test 4: Partition, write, heal, verify convergence ──────

    @Test
    @Order(4)
    @DisplayName("partitionAndWrite_allNodesConvergeAfterHeal")
    void partitionAndWrite_allNodesConvergeAfterHeal() {
        // 1. Setup: two accounts for a transfer
        UUID account1 = client.openAccount(UUID.randomUUID(), "INR");
        UUID account2 = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account1, new BigDecimal("10000.00"), UUID.randomUUID());
        client.deposit(account2, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;

        // Wait for full replication
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("10000.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, account2,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        // 2. Partition one follower
        chaosEngine.partitionNode(nodeIdFromIdx(followerIdx));
        sleep(2000);

        // 3. Perform a transfer on the healthy quorum
        client.transfer(account1, account2, new BigDecimal("3000.00"), UUID.randomUUID());

        // Verify on the non-partitioned nodes
        int otherFollowerIdx = (leaderIdx + 2) % 3;
        client.waitForBalance(leaderIdx, account1,
                new BigDecimal("7000.00"), Duration.ofSeconds(10));
        client.waitForBalance(otherFollowerIdx, account2,
                new BigDecimal("8000.00"), Duration.ofSeconds(10));

        // 4. Heal
        chaosEngine.healPartition(nodeIdFromIdx(followerIdx));
        sleep(5000);

        // 5. ALL three nodes converge
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("7000.00"), Duration.ofSeconds(15));
            client.waitForBalance(i, account2,
                    new BigDecimal("8000.00"), Duration.ofSeconds(15));
        }

        // 6. Conservation of money: total is still 15000
        BigDecimal totalMoney = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            BigDecimal b1 = client.getBalance(i, account1);
            BigDecimal b2 = client.getBalance(i, account2);
            totalMoney = b1.add(b2);
            assertThat(totalMoney)
                    .as("Conservation of money on node " + i)
                    .isEqualByComparingTo(new BigDecimal("15000.00"));
        }

        // Invariants
        for (int i = 0; i < 3; i++) {
            JsonNode result = client.runInvariants(i);
            assertThat(result.path("status").asText())
                    .as("Invariants on node " + i)
                    .isEqualTo("ALL_PASSED");
        }

        System.out.println("[BasicChaosTest] partitionAndWrite convergence PASSED.");
    }

    // ── Test 5: Toxiproxy healAll actually resets ────────────────

    @Test
    @Order(5)
    @DisplayName("healAll_removesAllFaults")
    void healAll_removesAllFaults() {
        // Inject multiple faults
        chaosEngine.slowNetwork(1, 500, 100);
        chaosEngine.slowNetwork(2, 500, 100);
        chaosEngine.partitionNode(3);

        // Heal everything
        chaosEngine.healAll();
        sleep(3000);

        // All nodes should be reachable and healthy
        for (int i = 0; i < 3; i++) {
            JsonNode status = client.getRaftStatus(i);
            assertThat(status.path("role").asText())
                    .as("Node " + i + " should have a Raft role")
                    .isIn("LEADER", "FOLLOWER");
        }

        // A write should succeed
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("100.00"), UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("100.00"), Duration.ofSeconds(10));
        }

        System.out.println("[BasicChaosTest] healAll PASSED.");
    }

    // ── Test 6: Event log records chaos actions ─────────────────

    @Test
    @Order(6)
    @DisplayName("chaosEventLog_recordsAllActions")
    void chaosEventLog_recordsAllActions() {
        // clearEventLog is called in @BeforeEach — start clean
        assertThat(chaosEngine.getEventLog()).isEmpty();

        // Perform several actions
        chaosEngine.partitionNode(1);
        chaosEngine.slowNetwork(2, 100);
        chaosEngine.healAll();

        // Verify log captured everything
        var log = chaosEngine.getEventLog();
        assertThat(log).hasSize(3);
        assertThat(log.get(0).action()).isEqualTo("PARTITION");
        assertThat(log.get(1).action()).isEqualTo("SLOW_NETWORK");
        assertThat(log.get(2).action()).isEqualTo("HEAL_ALL");

        // Timestamps should be monotonically increasing
        for (int i = 1; i < log.size(); i++) {
            assertThat(log.get(i).timestamp())
                    .isAfterOrEqualTo(log.get(i - 1).timestamp());
        }

        System.out.println("[BasicChaosTest] chaosEventLog PASSED.");
    }
}