package com.chaosledger.ledger.chaos;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-level chaos orchestrator for ChaosLedger.
 *
 * Wraps ToxiproxyClient with operations that make sense for a 3-node
 * Raft cluster:
 *   - partitionNode(id) — isolate a node from all peers
 *   - slowNetwork(id, ms) — add latency to a node's Raft traffic
 *   - flappingNetwork(id) — rapidly oscillating connectivity
 *   - healAll() — remove all faults, restore healthy cluster
 *
 * The ChaosEngine does NOT control Docker containers (that's
 * ClusterTestBase.stopContainer). It only manipulates the network
 * layer via Toxiproxy. This separation is deliberate:
 *   - Container stop = process crash (node dies completely)
 *   - Toxiproxy disable = network partition (node is alive but unreachable)
 *
 * The engine maintains an event log of every action for replay and
 * post-mortem analysis. This log is the seed of the deterministic
 * replay system in Week 17.
 *
 * Proxy naming convention:
 *   raft-node1, raft-node2, raft-node3
 *   These map to the Raft gRPC ports:
 *     raft-node1 → toxiproxy:19864 → ledger-1:9864
 *     raft-node2 → toxiproxy:19865 → ledger-2:9865
 *     raft-node3 → toxiproxy:19866 → ledger-3:9866
 *
 *   http-node1, http-node2, http-node3
 *   These map to the HTTP ports (for client-facing partitions):
 *     http-node1 → toxiproxy:18080 → ledger-1:8080
 *     http-node2 → toxiproxy:18081 → ledger-2:8081
 *     http-node3 → toxiproxy:18082 → ledger-3:8082
 */
@Slf4j
public class ChaosEngine {

    private static final Map<Integer, String> RAFT_PROXY_NAMES = Map.of(
            1, "raft-node1",
            2, "raft-node2",
            3, "raft-node3");

    private static final Map<Integer, String> HTTP_PROXY_NAMES = Map.of(
            1, "http-node1",
            2, "http-node2",
            3, "http-node3");

    private final ToxiproxyClient toxiproxy;
    private final List<ChaosEvent> eventLog = new CopyOnWriteArrayList<>();

    public ChaosEngine(ToxiproxyClient toxiproxy) {
        this.toxiproxy = toxiproxy;
    }

    // Proxy setup (called once at cluster boot)

    /**
     * Create all 6 proxies (3 Raft + 3 HTTP). Call this AFTER
     * Toxiproxy is healthy but BEFORE the cluster starts using
     * the proxied ports.
     *
     * The docker-compose.chaos.yml already has the port mappings.
     * This method tells Toxiproxy what to listen on and where to forward.
     */
    public void createAllProxies() {
        toxiproxy.createProxy("raft-node1", "0.0.0.0:19864", "ledger-1:9864");
        toxiproxy.createProxy("raft-node2", "0.0.0.0:19865", "ledger-2:9865");
        toxiproxy.createProxy("raft-node3", "0.0.0.0:19866", "ledger-3:9866");

        toxiproxy.createProxy("http-node1", "0.0.0.0:18080", "ledger-1:8080");
        toxiproxy.createProxy("http-node2", "0.0.0.0:18081", "ledger-2:8081");
        toxiproxy.createProxy("http-node3", "0.0.0.0:18082", "ledger-3:8082");

        recordEvent("SETUP", "Created all 6 proxies (3 Raft + 3 HTTP)");
        log.info("ChaosEngine: all 6 proxies created");
    }

    // Partition operations

    /**
     * Partition a node — disable its Raft proxy so no peer can reach it.
     * The node is still running and its HTTP port is still accessible
     * (unless you also call partitionNodeHttp).
     *
     * This simulates a SYMMETRIC network partition: the node can't send
     * or receive Raft messages. The remaining 2 nodes form a quorum
     * and elect a new leader (if the partitioned node was the leader).
     *
     * @param nodeId 1, 2, or 3
     */
    public void partitionNode(int nodeId) {
        String proxyName = raftProxy(nodeId);
        toxiproxy.setProxyEnabled(proxyName, false);
        recordEvent("PARTITION", "Node " + nodeId + " Raft traffic blocked");
        log.info("CHAOS: Node {} partitioned (Raft proxy '{}' disabled)", nodeId, proxyName);
    }

    /**
     * Partition a node by disabling ALL proxies briefly, then re-enabling
     * the non-partitioned ones. This breaks existing gRPC connections
     * and forces a leader election if the partitioned node was the leader.
     *
     * Why: disabling only the target's proxy blocks inbound traffic but
     * the node can still send heartbeats through other nodes' proxies.
     * Followers keep receiving heartbeats and never trigger an election.
     */
    public void partitionNodeHard(int nodeId) {
        for (int i = 1; i <= 3; i++) {
            toxiproxy.setProxyEnabled(raftProxy(i), false);
        }
        recordEvent("PARTITION_HARD", "Node " + nodeId + " hard partitioned — all proxies briefly disabled");
        log.info("CHAOS: All Raft proxies disabled to break connections for node {} partition", nodeId);

        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        for (int i = 1; i <= 3; i++) {
            if (i != nodeId) {
                toxiproxy.setProxyEnabled(raftProxy(i), true);
            }
        }
        recordEvent("PARTITION_HARD", "Non-partitioned proxies re-enabled, node " + nodeId + " remains isolated");
        log.info("CHAOS: Node {} isolated — other proxies re-enabled, election should follow", nodeId);
    }

    /**
     * Partition a node's HTTP as well — makes it completely unreachable
     * from tests and clients.
     */
    public void partitionNodeFull(int nodeId) {
        partitionNode(nodeId);
        String httpProxy = httpProxy(nodeId);
        toxiproxy.setProxyEnabled(httpProxy, false);
        recordEvent("PARTITION_FULL", "Node " + nodeId + " fully partitioned (Raft + HTTP)");
        log.info("CHAOS: Node {} fully partitioned (Raft + HTTP disabled)", nodeId);
    }

    /**
     * Heal a specific node's partition — re-enable its Raft proxy.
     */
    public void healPartition(int nodeId) {
        toxiproxy.setProxyEnabled(raftProxy(nodeId), true);
        toxiproxy.setProxyEnabled(httpProxy(nodeId), true);
        recordEvent("HEAL_PARTITION", "Node " + nodeId + " partition healed");
        log.info("CHAOS: Node {} partition healed", nodeId);
    }

    // Latency operations

    /**
     * Inject latency on a node's Raft traffic in BOTH directions.
     *
     * @param nodeId    1, 2, or 3
     * @param latencyMs base latency added to each packet
     * @param jitterMs  random jitter ± per packet
     */
    public void slowNetwork(int nodeId, long latencyMs, long jitterMs) {
        String proxy = raftProxy(nodeId);
        toxiproxy.addLatency(proxy, "latency-up-" + nodeId, latencyMs, jitterMs, "upstream");
        toxiproxy.addLatency(proxy, "latency-down-" + nodeId, latencyMs, jitterMs, "downstream");
        recordEvent("SLOW_NETWORK",
                "Node " + nodeId + " latency: " + latencyMs + "ms ± " + jitterMs + "ms");
        log.info("CHAOS: Node {} network slowed: {}ms ± {}ms", nodeId, latencyMs, jitterMs);
    }

    /**
     * Convenience overload: add latency with zero jitter.
     */
    public void slowNetwork(int nodeId, long latencyMs) {
        slowNetwork(nodeId, latencyMs, 0);
    }

    // Asymmetric partition (Week 13)

    public enum AsymmetricDirection { CANNOT_SEND, CANNOT_RECEIVE }

    /**
     * Block ONE direction of a node's Raft traffic, leaving the other open.
     * Uses a bandwidth toxic capped at 0 KB/s rather than a timeout toxic —
     * the connection stays open (as a real saturated/misrouted link would),
     * instead of repeatedly closing and reopening.
     */
    public void partitionAsymmetric(int nodeId, AsymmetricDirection direction) {
        String proxy = raftProxy(nodeId);
        String stream = direction == AsymmetricDirection.CANNOT_RECEIVE ? "upstream" : "downstream";
        String toxicName = "asym-" + direction.name().toLowerCase() + "-" + nodeId;
        toxiproxy.addBandwidth(proxy, toxicName, 0, stream);
        recordEvent("ASYMMETRIC_PARTITION", "Node " + nodeId + " "
                + (direction == AsymmetricDirection.CANNOT_RECEIVE
                ? "cannot receive (upstream throttled to 0)"
                : "cannot send (downstream throttled to 0)"));
        log.info("CHAOS: Node {} asymmetric partition — {}", nodeId, direction);
    }

    public void healAsymmetricPartition(int nodeId) {
        String proxy = raftProxy(nodeId);
        safeRemoveToxic(proxy, "asym-cannot_receive-" + nodeId);
        safeRemoveToxic(proxy, "asym-cannot_send-" + nodeId);
        recordEvent("HEAL_ASYMMETRIC", "Node " + nodeId + " asymmetric partition healed");
    }

    /**
     * Remove latency toxics from a node.
     */
    public void healSlowNetwork(int nodeId) {
        String proxy = raftProxy(nodeId);
        safeRemoveToxic(proxy, "latency-up-" + nodeId);
        safeRemoveToxic(proxy, "latency-down-" + nodeId);
        recordEvent("HEAL_SLOW", "Node " + nodeId + " latency removed");
        log.info("CHAOS: Node {} latency healed", nodeId);
    }

    // Bandwidth operations

    /**
     * Throttle a node's Raft bandwidth.
     *
     * @param nodeId 1, 2, or 3
     * @param rateKB throughput limit in KB/s (e.g. 1 = very slow)
     */
    public void throttleBandwidth(int nodeId, long rateKB) {
        String proxy = raftProxy(nodeId);
        toxiproxy.addBandwidth(proxy, "bw-up-" + nodeId, rateKB, "upstream");
        toxiproxy.addBandwidth(proxy, "bw-down-" + nodeId, rateKB, "downstream");
        recordEvent("THROTTLE", "Node " + nodeId + " bandwidth: " + rateKB + " KB/s");
        log.info("CHAOS: Node {} bandwidth throttled to {} KB/s", nodeId, rateKB);
    }

    /**
     * Remove bandwidth limit from a node.
     */
    public void healThrottle(int nodeId) {
        String proxy = raftProxy(nodeId);
        safeRemoveToxic(proxy, "bw-up-" + nodeId);
        safeRemoveToxic(proxy, "bw-down-" + nodeId);
        recordEvent("HEAL_THROTTLE", "Node " + nodeId + " bandwidth restored");
    }

    // Timeout (connection drop) operations

    /**
     * Force connection drops on a node's Raft traffic.
     *
     * @param nodeId    1, 2, or 3
     * @param timeoutMs time before connection is killed (0 = immediate)
     */
    public void dropConnections(int nodeId, long timeoutMs) {
        String proxy = raftProxy(nodeId);
        toxiproxy.addTimeout(proxy, "timeout-" + nodeId, timeoutMs, "upstream");
        recordEvent("DROP_CONNECTIONS",
                "Node " + nodeId + " connections dropping after " + timeoutMs + "ms");
        log.info("CHAOS: Node {} connections will drop after {}ms", nodeId, timeoutMs);
    }

    /**
     * Remove connection drop toxic from a node.
     */
    public void healDropConnections(int nodeId) {
        String proxy = raftProxy(nodeId);
        safeRemoveToxic(proxy, "timeout-" + nodeId);
        recordEvent("HEAL_DROP", "Node " + nodeId + " connection drops removed");
    }

    // Flapping network

    /**
     * Simulate a flapping network by slicing data into tiny fragments
     * with delays between them. This creates the kind of unreliable
     * connection where TCP stays open but data arrives in bursts.
     *
     * @param nodeId 1, 2, or 3
     */
    public void flappingNetwork(int nodeId) {
        String proxy = raftProxy(nodeId);
        toxiproxy.addSlicer(proxy, "slicer-" + nodeId, 10, 100000, "upstream");
        recordEvent("FLAPPING", "Node " + nodeId + " network flapping");
        log.info("CHAOS: Node {} network flapping (slicer toxic active)", nodeId);
    }

    /**
     * Heal a flapping network.
     */
    public void healFlapping(int nodeId) {
        String proxy = raftProxy(nodeId);
        safeRemoveToxic(proxy, "slicer-" + nodeId);
        recordEvent("HEAL_FLAPPING", "Node " + nodeId + " flapping healed");
    }

    // Global operations

    /**
     * Heal EVERYTHING. Removes all toxics from all proxies and
     * re-enables all proxies. Call this at the start and end of
     * every chaos scenario.
     */
    public void healAll() {
        toxiproxy.resetAll();
        recordEvent("HEAL_ALL", "All faults cleared, all proxies re-enabled");
        log.info("CHAOS: All faults healed — cluster network fully restored");
    }

    /**
     * Check if Toxiproxy is reachable.
     */
    public boolean isReady() {
        return toxiproxy.isHealthy();
    }

    // Event log

    /**
     * Get the full chaos event log. Each entry records the timestamp,
     * action type, and description.
     */
    public List<ChaosEvent> getEventLog() {
        return List.copyOf(eventLog);
    }

    /**
     * Clear the event log (call between scenarios).
     */
    public void clearEventLog() {
        eventLog.clear();
    }

    /**
     * A single chaos action recorded for replay and debugging.
     */
    public record ChaosEvent(
            Instant timestamp,
            String action,
            String description
    ) {}


    private String raftProxy(int nodeId) {
        String name = RAFT_PROXY_NAMES.get(nodeId);
        if (name == null) throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        return name;
    }

    private String httpProxy(int nodeId) {
        String name = HTTP_PROXY_NAMES.get(nodeId);
        if (name == null) throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        return name;
    }

    private void safeRemoveToxic(String proxyName, String toxicName) {
        try {
            toxiproxy.removeToxic(proxyName, toxicName);
        } catch (Exception e) {
            log.debug("Could not remove toxic '{}' from '{}': {}",
                    toxicName, proxyName, e.getMessage());
        }
    }

    private void recordEvent(String action, String description) {
        eventLog.add(new ChaosEvent(Instant.now(), action, description));
    }
}
