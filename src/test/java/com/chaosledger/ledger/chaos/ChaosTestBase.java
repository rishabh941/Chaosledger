// src/test/java/com/chaosledger/ledger/chaos/ChaosTestBase.java
package com.chaosledger.ledger.chaos;

import com.chaosledger.ledger.multinode.ClusterClient;
import com.chaosledger.ledger.multinode.ClusterTestBase;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for Week 11+ chaos tests.
 *
 * Extends the Week 10 pattern with Toxiproxy in the middle.
 *
 * Lifecycle:
 *   @BeforeAll  → Boot 7+3 containers (toxiproxy + 3 postgres + 3 ledger),
 *                 wait for health, create proxies, wait for leader election.
 *   @BeforeEach → healAll() + restart any killed containers + re-elect leader.
 *                 Every test starts from a clean, healthy, fully-connected cluster.
 *   @AfterAll   → Tear down everything.
 *
 * Environment guard: RUN_CHAOS_TESTS=true (separate from RUN_MULTINODE_TESTS
 * so you can run Week 10 tests without Toxiproxy).
 *
 * Preconditions:
 *   1. Docker running
 *   2. chaosledger-ledger:test image built
 *   3. RUN_CHAOS_TESTS=true
 */
@EnabledIfEnvironmentVariable(named = "RUN_CHAOS_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ChaosTestBase {

    protected static final String SERVICE_LEDGER_1 = "ledger-1";
    protected static final String SERVICE_LEDGER_2 = "ledger-2";
    protected static final String SERVICE_LEDGER_3 = "ledger-3";
    protected static final String SERVICE_TOXIPROXY = "toxiproxy";

    protected static final int PORT_LEDGER_1 = 8080;
    protected static final int PORT_LEDGER_2 = 8081;
    protected static final int PORT_LEDGER_3 = 8082;
    protected static final int PORT_TOXIPROXY = 8474;

    // HTTP ports on Toxiproxy for accessing ledger nodes through the proxy
    protected static final int PORT_HTTP_PROXY_1 = 18080;
    protected static final int PORT_HTTP_PROXY_2 = 18081;
    protected static final int PORT_HTTP_PROXY_3 = 18082;

    protected static ComposeContainer cluster;
    protected static ClusterClient client;
    protected static ChaosEngine chaosEngine;
    protected static ToxiproxyClient toxiproxyClient;

    @BeforeAll
    static void bootChaosCluster() {
        URL composeUrl = ChaosTestBase.class
                .getClassLoader()
                .getResource("docker-compose.chaos-test.yml");
        Objects.requireNonNull(composeUrl,
                "docker-compose.chaos-test.yml not found on test classpath");

        File composeFile = new File(composeUrl.getFile());

        cluster = new ComposeContainer(composeFile)
                // Wait for Toxiproxy
                .withExposedService(SERVICE_TOXIPROXY, PORT_TOXIPROXY,
                        Wait.forHttp("/version")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(1)))
                // Wait for ledger nodes (direct ports for health check)
                .withExposedService(SERVICE_LEDGER_1, PORT_LEDGER_1,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withExposedService(SERVICE_LEDGER_2, PORT_LEDGER_2,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withExposedService(SERVICE_LEDGER_3, PORT_LEDGER_3,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)))
                .withLocalCompose(true);

        cluster.start();

        // Build Toxiproxy client using mapped port
        String toxiHost = cluster.getServiceHost(SERVICE_TOXIPROXY, PORT_TOXIPROXY);
        int toxiPort = cluster.getServicePort(SERVICE_TOXIPROXY, PORT_TOXIPROXY);
        String toxiUrl = "http://" + toxiHost + ":" + toxiPort;
        toxiproxyClient = new ToxiproxyClient(toxiUrl);
        chaosEngine = new ChaosEngine(toxiproxyClient);

        // Create all 6 proxies
        chaosEngine.createAllProxies();

        // Build cluster client using DIRECT ports (not proxied)
        // Tests read from direct ports; chaos only affects Raft and
        // optionally HTTP through proxy ports
        List<String> nodeUrls = List.of(
                nodeUrl(SERVICE_LEDGER_1, PORT_LEDGER_1),
                nodeUrl(SERVICE_LEDGER_2, PORT_LEDGER_2),
                nodeUrl(SERVICE_LEDGER_3, PORT_LEDGER_3));
        client = new ClusterClient(nodeUrls);

        // Wait for leader election (may take longer with proxied Raft)
        int leader = client.waitForLeaderElection(Duration.ofSeconds(60));
        System.out.printf(
                "[ChaosTestBase] Cluster ready with Toxiproxy. Leader index=%d, urls=%s%n",
                leader, nodeUrls);
    }

    @AfterAll
    static void tearDownChaosCluster() {
        if (cluster != null) cluster.stop();
    }

    /**
     * Before every test: heal all faults, restart any killed containers,
     * and wait for a leader. Every chaos test starts from a clean state.
     */
    @BeforeEach
    void resetClusterState() {
        // Heal all network faults first
        try { chaosEngine.healAll(); }
        catch (Exception e) { System.err.println("healAll failed: " + e.getMessage()); }

        // Clear event log from prior test
        chaosEngine.clearEventLog();

        // Restart any stopped containers
        for (String svc : List.of(SERVICE_LEDGER_1, SERVICE_LEDGER_2, SERVICE_LEDGER_3)) {
            try { startContainer(svc); }
            catch (Exception ignored) { }
        }

        // Give containers a moment to stabilise
        sleep(2000);

        // Wait for leader
        client.waitForLeaderElection(Duration.ofSeconds(30));
    }

    // ── Container control (reused from ClusterTestBase pattern) ──

    protected static void stopContainer(String serviceName) {
        Container c = resolveContainer(serviceName);
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(c.getId()).withTimeout(3).exec();
    }

    protected static void startContainer(String serviceName) {
        Container c = resolveContainer(serviceName);
        if ("running".equalsIgnoreCase(c.getState())) return;
        var docker = DockerClientFactory.instance().client();
        docker.startContainerCmd(c.getId()).exec();
    }

    private static Container resolveContainer(String serviceName) {
        var docker = DockerClientFactory.instance().client();
        List<Container> all = docker.listContainersCmd().withShowAll(true).exec();

        List<Container> matches = new ArrayList<>();
        for (Container c : all) {
            for (String name : c.getNames()) {
                String stripped = name.startsWith("/") ? name.substring(1) : name;
                if (stripped.contains("-" + serviceName + "-")
                        || stripped.contains("_" + serviceName + "_")
                        || stripped.endsWith("-" + serviceName)
                        || stripped.endsWith("_" + serviceName)) {
                    matches.add(c);
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one container matching service '"
                            + serviceName + "'. Found " + matches.size());
        }
        return matches.get(0);
    }

    // ── Helpers ─────────────────────────────────────────────────

    protected static String nodeUrl(String service, int port) {
        String host = cluster.getServiceHost(service, port);
        int hostPort = cluster.getServicePort(service, port);
        return "http://" + host + ":" + hostPort;
    }

    protected static String serviceForNodeIdx(int idx) {
        return switch (idx) {
            case 0 -> SERVICE_LEDGER_1;
            case 1 -> SERVICE_LEDGER_2;
            case 2 -> SERVICE_LEDGER_3;
            default -> throw new IllegalArgumentException("Invalid node index: " + idx);
        };
    }

    /**
     * Convert node index (0-based, as used by ClusterClient) to
     * node ID (1-based, as used by ChaosEngine proxy names).
     */
    protected static int nodeIdFromIdx(int idx) {
        return idx + 1;
    }

    protected static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}