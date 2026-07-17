package com.chaosledger.ledger.multinode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 10 — InvariantMultiNodeTest.
 *
 * Runs the same 5 invariants (from Phase 2) against every node's local Postgres
 * and asserts they all pass. This is the strongest cross-cutting check: if
 * replication drifted, HLC misordered events, or the state machine dropped a
 * write, at least one invariant on at least one node would fail.
 */
class InvariantMultiNodeTest extends ManualClusterTestBase {

    @Test
    @DisplayName("All 5 invariants pass on every node after mixed operations")
    void allInvariants_passOnEveryNode() {
        UUID acctA = client.openAccount(UUID.randomUUID(), "INR");
        UUID acctB = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(acctA, new BigDecimal("500.00"), UUID.randomUUID());
        client.deposit(acctB, new BigDecimal("300.00"), UUID.randomUUID());
        client.transfer(acctA, acctB, new BigDecimal("100.00"), UUID.randomUUID());
        client.withdraw(acctB, new BigDecimal("50.00"), UUID.randomUUID());

        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, acctA, new BigDecimal("400.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, acctB, new BigDecimal("350.00"), Duration.ofSeconds(10));
        }

        for (int i = 0; i < client.nodeCount(); i++) {
            JsonNode result = client.runInvariants(i);
            JsonNode status = result.path("status");
            assertThat(status.path("invariantCount").asInt())
                    .as("Node " + i + " should register all 5 invariants")
                    .isEqualTo(5);
            assertThat(status.path("failed").asInt())
                    .as("Node " + i + " has failing invariants: "
                            + result.path("invariants").toString())
                    .isZero();
            assertThat(status.path("errors").asInt())
                    .as("Node " + i + " has errored invariants: "
                            + result.path("invariants").toString())
                    .isZero();
            assertThat(status.path("allPassing").asBoolean())
                    .as("Node " + i + " reported allPassing=false. Full response: "
                            + result)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Conservation-of-money holds identically on all three nodes")
    void conservationOfMoney_holdsAcrossAllNodes() {
        UUID acctA = client.openAccount(UUID.randomUUID(), "INR");
        UUID acctB = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(acctA, new BigDecimal("1000.00"), UUID.randomUUID());
        client.deposit(acctB, new BigDecimal("2000.00"), UUID.randomUUID());

        // Three transfers with a net zero across the pair
        client.transfer(acctA, acctB, new BigDecimal("500.00"), UUID.randomUUID());
        client.transfer(acctB, acctA, new BigDecimal("300.00"), UUID.randomUUID());
        client.transfer(acctA, acctB, new BigDecimal("200.00"), UUID.randomUUID());

        // A: 1000 - 500 + 300 - 200 = 600
        // B: 2000 + 500 - 300 + 200 = 2400
        for (int i = 0; i < client.nodeCount(); i++) {
            client.waitForBalance(i, acctA, new BigDecimal("600.00"), Duration.ofSeconds(10));
            client.waitForBalance(i, acctB, new BigDecimal("2400.00"), Duration.ofSeconds(10));
        }

        for (int i = 0; i < client.nodeCount(); i++) {
            JsonNode result = client.runInvariants(i);
            JsonNode invariants = result.path("invariants");
            boolean foundConservation = false;
            for (JsonNode inv : invariants) {
                if ("conservation-of-money".equals(inv.path("name").asText())) {
                    foundConservation = true;
                    assertThat(inv.path("status").asText())
                            .as("Conservation invariant failed on node " + i + ": " + inv)
                            .isEqualTo("PASSED");
                }
            }
            assertThat(foundConservation)
                    .as("Node " + i + " did not report the conservation-of-money invariant")
                    .isTrue();
        }
    }

//    @Test
//    @Disabled("Requires Docker API — skipped on Windows")
//    @DisplayName("All invariants still pass on survivors after a leader failover")
//    void allInvariants_passAfterLeaderFailover() {
//        UUID acctA = client.openAccount(UUID.randomUUID(), "INR");
//        client.deposit(acctA, new BigDecimal("500.00"), UUID.randomUUID());
//        for (int i = 0; i < client.nodeCount(); i++) {
//            client.waitForBalance(i, acctA, new BigDecimal("500.00"), Duration.ofSeconds(10));
//        }
//
//        int oldLeaderIdx = client.findLeader();
//        stopContainer(serviceForNodeIdx(oldLeaderIdx));
//        int newLeaderIdx = client.waitForLeaderElection(Duration.ofSeconds(10));
//        assertThat(newLeaderIdx).isNotEqualTo(oldLeaderIdx);
//
//        UUID acctB = client.openAccount(UUID.randomUUID(), "INR");
//        client.deposit(acctB, new BigDecimal("250.00"), UUID.randomUUID());
//        client.transfer(acctA, acctB, new BigDecimal("100.00"), UUID.randomUUID());
//
//        for (int i = 0; i < client.nodeCount(); i++) {
//            if (i == oldLeaderIdx) continue;
//            client.waitForBalance(i, acctA, new BigDecimal("400.00"), Duration.ofSeconds(10));
//            client.waitForBalance(i, acctB, new BigDecimal("350.00"), Duration.ofSeconds(10));
//        }
//
//        for (int i = 0; i < client.nodeCount(); i++) {
//            if (i == oldLeaderIdx) continue;
//            JsonNode result = client.runInvariants(i);
//            assertThat(result.path("status").path("allPassing").asBoolean())
//                    .as("Node " + i + " has failing invariants after failover: " + result)
//                    .isTrue();
//        }

        // @BeforeEach in the base class will restart the killed node
        // before the next test.
    //}
}