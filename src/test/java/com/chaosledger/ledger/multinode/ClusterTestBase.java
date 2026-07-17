package com.chaosledger.ledger.multinode;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Base class for Week 10 multi-node integration tests.
 *
 * Preconditions:
 *   1. Docker running locally
 *   2. A pre-built application image tagged chaosledger-ledger:test.
 *      Build with:
 *          mvn clean package -DskipTests
 *          docker build -t chaosledger-ledger:test .
 *   3. The environment variable RUN_MULTINODE_TESTS=true
 *
 * Without RUN_MULTINODE_TESTS=true these tests are SKIPPED. This keeps
 * `mvn clean test` fast for everyday development.
 */
@EnabledIfEnvironmentVariable(named = "RUN_MULTINODE_TESTS", matches = "true")
public abstract class ClusterTestBase {

    protected static final String SERVICE_LEDGER_1 = "ledger-1";
    protected static final String SERVICE_LEDGER_2 = "ledger-2";
    protected static final String SERVICE_LEDGER_3 = "ledger-3";
    protected static final int PORT_LEDGER_1 = 8080;
    protected static final int PORT_LEDGER_2 = 8081;
    protected static final int PORT_LEDGER_3 = 8082;

    protected static ComposeContainer cluster;
    protected static ClusterClient client;

    @BeforeAll
    static void bootCluster() {
        URL composeUrl = ClusterTestBase.class
                .getClassLoader()
                .getResource("docker-compose.test.yml");
        Objects.requireNonNull(composeUrl,
                "docker-compose.test.yml not found on test classpath");

        File composeFile = new File(composeUrl.getFile());

        cluster = new ComposeContainer(composeFile)
                .withExposedService(SERVICE_LEDGER_1, PORT_LEDGER_1,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(2)))
                .withExposedService(SERVICE_LEDGER_2, PORT_LEDGER_2,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(2)))
                .withExposedService(SERVICE_LEDGER_3, PORT_LEDGER_3,
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(2)))
                .withLocalCompose(true);

        cluster.start();

        List<String> nodeUrls = List.of(
                nodeUrl(SERVICE_LEDGER_1, PORT_LEDGER_1),
                nodeUrl(SERVICE_LEDGER_2, PORT_LEDGER_2),
                nodeUrl(SERVICE_LEDGER_3, PORT_LEDGER_3));
        client = new ClusterClient(nodeUrls);

        int leader = client.waitForLeaderElection(Duration.ofSeconds(45));
        System.out.printf(
                "[ClusterTestBase] Cluster ready. Leader index=%d, urls=%s%n",
                leader, nodeUrls);
    }

    @AfterAll
    static void tearDownCluster() {
        if (cluster != null) cluster.stop();
    }

    /**
     * Before every test: restart any container that was stopped by a prior test
     * so each test starts with a healthy 3-node cluster.
     */
    @BeforeEach
    void ensureHealthyCluster() {
        for (String svc : List.of(SERVICE_LEDGER_1, SERVICE_LEDGER_2, SERVICE_LEDGER_3)) {
            try { startContainer(svc); }
            catch (Exception ignored) { }
        }
        try { Thread.sleep(1000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        client.waitForLeaderElection(Duration.ofSeconds(30));
    }

    // ── Container control (used by LeaderFailoverTest) ──────────

    protected static void stopContainer(String serviceName) {
        Container c = resolveContainer(serviceName);
        DockerClient docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(c.getId())
                .withTimeout(3)
                .exec();
    }

    protected static void startContainer(String serviceName) {
        Container c = resolveContainer(serviceName);
        if ("running".equalsIgnoreCase(c.getState())) return;
        DockerClient docker = DockerClientFactory.instance().client();
        docker.startContainerCmd(c.getId()).exec();
    }

    protected static String containerState(String serviceName) {
        return resolveContainer(serviceName).getState();
    }

    private static Container resolveContainer(String serviceName) {
        DockerClient docker = DockerClientFactory.instance().client();
        List<Container> all = docker.listContainersCmd()
                .withShowAll(true)
                .exec();

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
                            + serviceName + "'. Found " + matches.size()
                            + " — Docker names: "
                            + all.stream().flatMap(c -> java.util.Arrays.stream(c.getNames())).toList());
        }
        return matches.get(0);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static String nodeUrl(String service, int port) {
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
}