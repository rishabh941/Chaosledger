package com.chaosledger.ledger.multinode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;


import java.time.Duration;
import java.util.List;

/**
 * Alternative test base for environments where Testcontainers cannot
 * connect to Docker Desktop's API (known Windows+WSL2 issue).
 *
 * Instead of booting containers via ComposeContainer, this base assumes
 * you already started the cluster manually:
 *
 *     docker compose up -d
 *
 * Then run:
 *     RUN_MULTINODE_TESTS=true mvn test -Dtest='...'
 *
 * The cluster must be running on localhost ports 8080, 8081, 8082.
 */

@EnabledIfEnvironmentVariable(named = "RUN_MULTINODE_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class ManualClusterTestBase {

    protected static ClusterClient client;

    @BeforeAll
    static void connectToRunningCluster() {
        List<String> nodeUrls = List.of(
                "http://localhost:8080",
                "http://localhost:8081",
                "http://localhost:8082");
        client = new ClusterClient(nodeUrls);

        System.out.println("[ManualClusterTestBase] Connecting to existing cluster...");
        int leader = client.waitForLeaderElection(Duration.ofSeconds(30));
        System.out.printf(
                "[ManualClusterTestBase] Cluster ready. Leader index=%d%n", leader);
    }

    @BeforeEach
    void ensureLeaderExists() {
        client.waitForLeaderElection(Duration.ofSeconds(15));
    }
}