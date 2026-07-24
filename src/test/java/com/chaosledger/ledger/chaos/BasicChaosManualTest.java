package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual version of BasicChaosTest for Windows/WSL2.
 *
 * Prerequisite:
 *   docker compose -f docker-compose.chaos.yml up -d
 *   Wait 30-60 seconds for leader election.
 *
 * Run with:
 *   set RUN_CHAOS_TESTS=true
 *   mvn test -Dtest=BasicChaosManualTest
 */
@EnabledIfEnvironmentVariable(named = "RUN_CHAOS_TESTS", matches = "true")
public class BasicChaosManualTest extends ManualChaosTestBase {

    @Test
    @Order(1)
    @DisplayName("partitionFollower_clusterContinuesToWork")
    void partitionFollower_clusterContinuesToWork() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("1000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;
        int followerNodeId = nodeIdFromIdx(followerIdx);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("1000.00"), Duration.ofSeconds(10));
        }

        chaosEngine.partitionNode(followerNodeId);
        sleep(2000);

        client.deposit(accountId, new BigDecimal("500.00"), UUID.randomUUID());
        client.waitForBalance(leaderIdx, accountId,
                new BigDecimal("1500.00"), Duration.ofSeconds(10));

        chaosEngine.healPartition(followerNodeId);
        sleep(3000);

        client.waitForBalance(followerIdx, accountId,
                new BigDecimal("1500.00"), Duration.ofSeconds(15));

        System.out.println("[BasicChaosManualTest] partitionFollower PASSED");
    }

    @Test
    @Order(2)
    @DisplayName("partitionLeader_newLeaderElected_writesSucceed")
    void partitionLeader_newLeaderElected_writesSucceed() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("2000.00"), UUID.randomUUID());

        int oldLeaderIdx = client.findLeader();
        int oldLeaderNodeId = nodeIdFromIdx(oldLeaderIdx);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("2000.00"), Duration.ofSeconds(10));
        }

        chaosEngine.partitionNode(oldLeaderNodeId);
        sleep(1000);

        int survivor1 = (oldLeaderIdx + 1) % 3;
        int survivor2 = (oldLeaderIdx + 2) % 3;

        long electionStart = System.currentTimeMillis();
        int newLeaderIdx = -1;
        while (System.currentTimeMillis() - electionStart < 15_000) {
            try {
                JsonNode s1 = client.getRaftStatus(survivor1);
                if ("LEADER".equals(s1.path("role").asText(""))) {
                    newLeaderIdx = survivor1;
                    break;
                }
            } catch (Exception ignored) {}
            try {
                JsonNode s2 = client.getRaftStatus(survivor2);
                if ("LEADER".equals(s2.path("role").asText(""))) {
                    newLeaderIdx = survivor2;
                    break;
                }
            } catch (Exception ignored) {}
            sleep(250);
        }

        assertThat(newLeaderIdx)
                .as("A new leader should be elected")
                .isGreaterThanOrEqualTo(0);
        assertThat(newLeaderIdx)
                .as("New leader differs from old")
                .isNotEqualTo(oldLeaderIdx);

        System.out.printf("[BasicChaosManualTest] New leader: node %d (took %d ms)%n",
                newLeaderIdx, System.currentTimeMillis() - electionStart);

        client.findLeader();
        client.deposit(accountId, new BigDecimal("300.00"), UUID.randomUUID());

        client.waitForBalance(survivor1, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(10));
        client.waitForBalance(survivor2, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(10));

        chaosEngine.healPartition(oldLeaderNodeId);
        sleep(5000);

        client.waitForBalance(oldLeaderIdx, accountId,
                new BigDecimal("2300.00"), Duration.ofSeconds(15));

        System.out.println("[BasicChaosManualTest] partitionLeader PASSED");
    }

    @Test
    @Order(3)
    @DisplayName("slowNetwork_clusterStillFunctions")
    void slowNetwork_clusterStillFunctions() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("5000.00"), UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        for (int nodeId = 1; nodeId <= 3; nodeId++) {
            chaosEngine.slowNetwork(nodeId, 200, 50);
        }
        sleep(1000);

        long writeStart = System.currentTimeMillis();
        client.deposit(accountId, new BigDecimal("100.00"), UUID.randomUUID());
        System.out.printf("[BasicChaosManualTest] Write under latency: %d ms%n",
                System.currentTimeMillis() - writeStart);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5100.00"), Duration.ofSeconds(30));
        }

        chaosEngine.healAll();
        sleep(1000);

        client.deposit(accountId, new BigDecimal("50.00"), UUID.randomUUID());
        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("5150.00"), Duration.ofSeconds(10));
        }

        System.out.println("[BasicChaosManualTest] slowNetwork PASSED");
    }

    @Test
    @Order(4)
    @DisplayName("partitionAndWrite_allNodesConvergeAfterHeal")
    void partitionAndWrite_allNodesConvergeAfterHeal() {
        UUID account1 = client.openAccount(UUID.randomUUID(), "INR");
        UUID account2 = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account1, new BigDecimal("10000.00"), UUID.randomUUID());
        client.deposit(account2, new BigDecimal("5000.00"), UUID.randomUUID());

        int leaderIdx = client.findLeader();
        int followerIdx = (leaderIdx + 1) % 3;

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("10000.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, account2,
                    new BigDecimal("5000.00"), Duration.ofSeconds(10));
        }

        chaosEngine.partitionNode(nodeIdFromIdx(followerIdx));
        sleep(2000);

        client.transfer(account1, account2, new BigDecimal("3000.00"), UUID.randomUUID());

        chaosEngine.healPartition(nodeIdFromIdx(followerIdx));
        sleep(5000);

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, account1,
                    new BigDecimal("7000.00"), Duration.ofSeconds(15));
            client.waitForBalance(i, account2,
                    new BigDecimal("8000.00"), Duration.ofSeconds(15));
        }

        for (int i = 0; i < 3; i++) {
            BigDecimal b1 = client.getBalance(i, account1);
            BigDecimal b2 = client.getBalance(i, account2);
            assertThat(b1.add(b2))
                    .as("Conservation of money on node " + i)
                    .isEqualByComparingTo(new BigDecimal("15000.00"));
        }

        System.out.println("[BasicChaosManualTest] partitionAndWrite convergence PASSED");
    }

    @Test
    @Order(5)
    @DisplayName("healAll_removesAllFaults")
    void healAll_removesAllFaults() {
        chaosEngine.slowNetwork(1, 500, 100);
        chaosEngine.slowNetwork(2, 500, 100);
        chaosEngine.partitionNode(3);

        chaosEngine.healAll();
        sleep(3000);

        for (int i = 0; i < 3; i++) {
            JsonNode status = client.getRaftStatus(i);
            assertThat(status.path("role").asText())
                    .as("Node " + i + " should have a Raft role")
                    .isIn("LEADER", "FOLLOWER");
        }

        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("100.00"), UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("100.00"), Duration.ofSeconds(10));
        }

        System.out.println("[BasicChaosManualTest] healAll PASSED");
    }

    @Test
    @Order(6)
    @DisplayName("chaosEventLog_recordsAllActions")
    void chaosEventLog_recordsAllActions() {
        assertThat(chaosEngine.getEventLog()).isEmpty();

        chaosEngine.partitionNode(1);
        chaosEngine.slowNetwork(2, 100);
        chaosEngine.healAll();

        var log = chaosEngine.getEventLog();
        assertThat(log).hasSize(3);
        assertThat(log.get(0).action()).isEqualTo("PARTITION");
        assertThat(log.get(1).action()).isEqualTo("SLOW_NETWORK");
        assertThat(log.get(2).action()).isEqualTo("HEAL_ALL");

        for (int i = 1; i < log.size(); i++) {
            assertThat(log.get(i).timestamp())
                    .isAfterOrEqualTo(log.get(i - 1).timestamp());
        }

        System.out.println("[BasicChaosManualTest] chaosEventLog PASSED");
    }
}
