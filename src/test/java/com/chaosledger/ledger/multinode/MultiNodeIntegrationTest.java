package com.chaosledger.ledger.multinode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 10 — MultiNodeIntegrationTest.
 *
 * Proves that Raft replication moves committed state from the leader to
 * every follower and that follower reads return the same data as the
 * leader eventually.
 */
class MultiNodeIntegrationTest extends ManualClusterTestBase {

    @Test
    @DisplayName("Write on leader → all followers see the balance eventually")
    void writeToLeader_readsPropagateToAllFollowers() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("1000.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId,
                    new BigDecimal("1000.00"), Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName("Multiple writes on leader → all nodes have identical event counts")
    void multipleWrites_allNodesHaveIdenticalEventCounts() {
        long baseline = client.getEventCount(0);

        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("500.00"), UUID.randomUUID());
        client.deposit(accountId, new BigDecimal("200.00"), UUID.randomUUID());
        client.withdraw(accountId, new BigDecimal("100.00"), UUID.randomUUID());

        // 4 domain events per account: AccountOpened, Deposit, Deposit, Withdraw
        long expected = baseline + 4;

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForEventCount(i, expected, Duration.ofSeconds(10));
        }

        long count0 = client.getEventCount(0);
        long count1 = client.getEventCount(1);
        long count2 = client.getEventCount(2);
        assertThat(count0).isEqualTo(count1);
        assertThat(count1).isEqualTo(count2);
    }

    @Test
    @DisplayName("Withdraws and deposits: balances match across all three nodes")
    void interleavedOps_allNodesHaveSameBalance() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("1000.00"), UUID.randomUUID());
        client.withdraw(accountId, new BigDecimal("300.00"), UUID.randomUUID());
        client.deposit(accountId, new BigDecimal("50.00"), UUID.randomUUID());

        BigDecimal expected = new BigDecimal("750.00");

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId, expected, Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName("Read from every follower returns the same account view as the leader")
    void readFromFollower_returnsSameDataAsLeader() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("42.00"), UUID.randomUUID());

        client.waitForBalance(0, accountId, new BigDecimal("42.00"), Duration.ofSeconds(10));
        client.waitForBalance(1, accountId, new BigDecimal("42.00"), Duration.ofSeconds(10));
        client.waitForBalance(2, accountId, new BigDecimal("42.00"), Duration.ofSeconds(10));

        JsonNode fromLeader = client.getAccount(client.findLeader(), accountId);
        for (int i = 0; i < client.nodeCount(); i++) {
            JsonNode fromNode = client.getAccount(i, accountId);
            assertThat(fromNode.path("accountId").asText())
                    .isEqualTo(fromLeader.path("accountId").asText());
            assertThat(fromNode.path("ownerId").asText())
                    .isEqualTo(fromLeader.path("ownerId").asText());
            assertThat(fromNode.path("currency").asText())
                    .isEqualTo(fromLeader.path("currency").asText());
            assertThat(fromNode.path("balance").asText())
                    .isEqualTo(fromLeader.path("balance").asText());
            assertThat(fromNode.path("version").asLong())
                    .isEqualTo(fromLeader.path("version").asLong());
        }
    }

    @Test
    @DisplayName("Transfer between two accounts replicates atomically to all nodes")
    void transfer_replicatesConsistentlyAcrossNodes() {
        UUID fromId = client.openAccount(UUID.randomUUID(), "INR");
        UUID toId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(fromId, new BigDecimal("1000.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, fromId, new BigDecimal("1000.00"), Duration.ofSeconds(10));
        }

        client.transfer(fromId, toId, new BigDecimal("300.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, fromId, new BigDecimal("700.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, toId, new BigDecimal("300.00"), Duration.ofSeconds(10));
        }
    }
}