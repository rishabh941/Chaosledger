// src/test/java/com/chaosledger/ledger/chaos/ManualChaosTestBase.java
package com.chaosledger.ledger.chaos;

import com.chaosledger.ledger.multinode.ClusterClient;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

/**
 * Manual test base for chaos tests on Windows/WSL2.
 *
 * Key difference from ChaosTestBase: uses docker compose CLI for
 * container stop/start instead of Testcontainers Docker API.
 *
 * Connects to ledger nodes via DIRECT ports (8080-8082) for reads,
 * NOT through Toxiproxy HTTP proxies (18080-18082). This is critical:
 * when a container is stopped, the proxy returns EOF immediately
 * (TCP connect succeeds to Toxiproxy but upstream is dead). Direct
 * ports give a clean "connection refused" that Awaitility can retry.
 *
 * Prerequisite:
 *   docker compose -f docker-compose.chaos.yml up -d
 *   Wait 60 seconds for leader election.
 *
 * Run with:
 *   $env:RUN_CHAOS_TESTS="true"
 *   ./mvnw test -Dtest=CatalogScenarioManualTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ManualChaosTestBase {

    protected static ClusterClient client;
    protected static ChaosEngine chaosEngine;
    protected static ToxiproxyClient toxiproxyClient;

    @BeforeAll
    static void connectToRunningCluster() {
        // Connect to Toxiproxy admin API
        toxiproxyClient = new ToxiproxyClient("http://localhost:8474");
        chaosEngine = new ChaosEngine(toxiproxyClient);

        if (!chaosEngine.isReady()) {
            throw new IllegalStateException(
                    "Toxiproxy not reachable at localhost:8474. "
                            + "Start the cluster: docker compose -f docker-compose.chaos.yml up -d");
        }

        // Connect to cluster via DIRECT ports (not proxied).
        // Direct ports bypass Toxiproxy so reads work even when
        // the HTTP proxy is disabled or the container was just restarted.
        List<String> nodeUrls = List.of(
                "http://localhost:8080",
                "http://localhost:8081",
                "http://localhost:8082");
        client = new ClusterClient(nodeUrls);

        // Make sure all containers are running (previous test run
        // may have left some stopped)
        restartAllContainers();
        sleep(5000);

        System.out.println("[ManualChaosTestBase] Connecting to chaos cluster (direct ports)...");
        int leader = client.waitForLeaderElection(Duration.ofSeconds(60));
        System.out.printf("[ManualChaosTestBase] Cluster ready. Leader index=%d%n", leader);
    }

    @BeforeEach
    void resetClusterState() {
        // 1. Heal all network faults
        try { chaosEngine.healAll(); }
        catch (Exception e) { System.err.println("healAll failed: " + e.getMessage()); }

        // 2. Clear event log
        chaosEngine.clearEventLog();

        // 3. Restart any stopped containers from previous test
        restartAllContainers();

        // 4. Wait for containers to stabilise
        sleep(3000);

        // 5. Wait for leader election
        client.waitForLeaderElection(Duration.ofSeconds(30));
    }

    // ── Container control via docker compose CLI ────────────────

    protected static void stopContainer(String serviceName) {
        runDockerCompose("stop", serviceName);
        System.out.printf("[ManualChaosTestBase] Stopped container: %s%n", serviceName);
    }

    protected static void startContainer(String serviceName) {
        runDockerCompose("start", serviceName);
        System.out.printf("[ManualChaosTestBase] Started container: %s%n", serviceName);
    }

    protected static String serviceForNodeIdx(int idx) {
        return switch (idx) {
            case 0 -> "ledger-1";
            case 1 -> "ledger-2";
            case 2 -> "ledger-3";
            default -> throw new IllegalArgumentException("Invalid node index: " + idx);
        };
    }

    protected static int nodeIdFromIdx(int idx) { return idx + 1; }

    protected static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ── Internal helpers ────────────────────────────────────────

    /**
     * Restart all 3 ledger containers. Safe to call even if they're
     * already running — docker compose start is a no-op for running
     * containers.
     */
    private static void restartAllContainers() {
        for (String svc : List.of("ledger-1", "ledger-2", "ledger-3")) {
            try { startContainer(svc); }
            catch (Exception e) {
                System.err.println("Could not start " + svc + ": " + e.getMessage());
            }
        }
    }

    /**
     * Create a true symmetric partition of a node.
     *
     * Simply disabling the node's own Raft proxy is NOT sufficient
     * because the node can still send heartbeats outbound through
     * other nodes' proxies. To force an election, we must:
     *   1. Disable ALL Raft proxies (kills all connections)
     *   2. Wait for Raft election timeout to fire (~2-3s)
     *   3. Re-enable only the survivors' proxies
     *
     * Result: survivors can talk to each other, old leader is isolated,
     * and old leader's gRPC connections are dead.
     */
    protected static void partitionNodeSymmetric(int nodeId) {
        // Step 1: Kill ALL Raft connections
        chaosEngine.partitionNode(1);
        chaosEngine.partitionNode(2);
        chaosEngine.partitionNode(3);

        // Step 2: Wait for Raft election timeout to fire.
        // Election timeout is typically 1000-2000ms in the config.
        // 4 seconds gives enough margin.
        sleep(4000);

        // Step 3: Re-enable ONLY the survivors' proxies
        for (int id = 1; id <= 3; id++) {
            if (id != nodeId) {
                chaosEngine.healPartition(id);
            }
        }
        // The partitioned node's proxy stays disabled
    }

    private static void runDockerCompose(String action, String service) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "compose",
                    "-f", "docker-compose.chaos.yml",
                    action, service
            );
            pb.inheritIO();
            Process p = pb.start();
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException(
                        "docker compose " + action + " " + service + " timed out after 30s");
            }
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "docker compose " + action + " " + service +
                                " failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during docker compose " + action, e);
        } catch (java.io.IOException e) {
            throw new RuntimeException(
                    "Failed to run docker compose " + action + ": " + e.getMessage(), e);
        }
    }
}