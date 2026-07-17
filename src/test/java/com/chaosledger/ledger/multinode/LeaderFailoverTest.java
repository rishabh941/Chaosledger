package com.chaosledger.ledger.multinode;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 10 — LeaderFailoverTest.
 *
 * The single most important guarantee of a Raft-replicated ledger:
 *   1. If the leader crashes, the cluster elects a new one within a few seconds.
 *   2. Any write that was committed BEFORE the crash is safe on the surviving majority.
 *   3. New writes go through the new leader without loss.
 *   4. The crashed node catches up from the Raft log when it restarts.
 */
class LeaderFailoverTest extends ClusterTestBase {

    @Test
    @Disabled("Requires Docker API — skipped on Windows")
    @DisplayName("Killing the leader triggers a new election within 5 seconds")
    void killLeader_newLeaderElectedWithinFiveSeconds() {
        int oldLeaderIdx = client.findLeader();
        String oldLeaderService = serviceForNodeIdx(oldLeaderIdx);
        System.out.println("[failover] Old leader: " + oldLeaderService);

        long tStart = System.currentTimeMillis();
        stopContainer(oldLeaderService);

        Awaitility.await("new leader election")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    int newLeader = client.findLeaderOrMinusOne();
                    assertThat(newLeader)
                            .as("Expected a new leader after killing " + oldLeaderService)
                            .isGreaterThanOrEqualTo(0)
                            .isNotEqualTo(oldLeaderIdx);
                });

        long elapsed = System.currentTimeMillis() - tStart;
        System.out.printf("[failover] New leader elected in %dms%n", elapsed);
        assertThat(elapsed).isLessThan(5000);
    }

    @Test
    @Disabled("Requires Docker API — skipped on Windows")
    @DisplayName("Writes committed before the crash are visible on survivors")
    void killLeader_committedWritesAreNotLost() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("777.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId, new BigDecimal("777.00"), Duration.ofSeconds(10));
        }

        int oldLeaderIdx = client.findLeader();
        stopContainer(serviceForNodeIdx(oldLeaderIdx));
        client.waitForLeaderElection(Duration.ofSeconds(10));

        for (int i = 0; i < client.nodeCount(); i++) {
            if (i == oldLeaderIdx) continue;
            BigDecimal balance = client.getBalance(i, accountId);
            assertThat(balance)
                    .as("Committed write lost on surviving node " + i)
                    .isEqualByComparingTo(new BigDecimal("777.00"));
        }
    }

    @Test
    @Disabled("Requires Docker API — skipped on Windows")
    @DisplayName("The new leader accepts writes after the old one is gone")
    void killLeader_newLeaderAcceptsWrites() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("100.00"), UUID.randomUUID());

        int oldLeaderIdx = client.findLeader();
        stopContainer(serviceForNodeIdx(oldLeaderIdx));
        int newLeaderIdx = client.waitForLeaderElection(Duration.ofSeconds(10));
        assertThat(newLeaderIdx).isNotEqualTo(oldLeaderIdx);

        client.deposit(accountId, new BigDecimal("50.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            if (i == oldLeaderIdx) continue;
            client.waitForBalance(i, accountId, new BigDecimal("150.00"), Duration.ofSeconds(10));
        }
    }

    @Test
    @Disabled("Requires Docker API — skipped on Windows")
    @DisplayName("The restarted node catches up from the Raft log")
    void restartedLeader_catchesUpFromRaftLog() {
        UUID accountId = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(accountId, new BigDecimal("200.00"), UUID.randomUUID());
        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, accountId, new BigDecimal("200.00"), Duration.ofSeconds(10));
        }

        int oldLeaderIdx = client.findLeader();
        String oldLeaderService = serviceForNodeIdx(oldLeaderIdx);
        stopContainer(oldLeaderService);
        client.waitForLeaderElection(Duration.ofSeconds(10));

        client.deposit(accountId, new BigDecimal("300.00"), UUID.randomUUID());
        client.deposit(accountId, new BigDecimal("400.00"), UUID.randomUUID());

        startContainer(oldLeaderService);

        Awaitility.await("restarted node rejoins with correct balance")
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    BigDecimal balance = client.getBalance(oldLeaderIdx, accountId);
                    assertThat(balance).isEqualByComparingTo(new BigDecimal("900.00"));
                });
    }
}