package com.chaosledger.ledger.infrastructure.raft;

import com.chaosledger.ledger.domain.hlc.HybridLogicalClock;
import com.chaosledger.ledger.infrastructure.eventstore.PostgresEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.client.RaftClient;
import com.chaosledger.ledger.domain.IdempotencyStore;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.util.TimeDuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration that creates and wires the Raft infrastructure:
 * RaftGroup, RaftServer, RaftClient, and LedgerStateMachine.
 *
 * Only active when raft.enabled=true. In single-node mode (tests,
 * local development), this entire configuration is skipped.
 */
@Configuration
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
@Slf4j
public class RaftConfiguration {

    @Value("${raft.node.id}")
    private String nodeId;

    @Value("${raft.node.host}")
    private String nodeHost;

    @Value("${raft.node.port}")
    private int nodePort;

    @Value("${raft.peers}")
    private String peersConfig;

    @Value("${raft.group.id}")
    private String groupId;

    @Value("${raft.storage.dir}")
    private String storageDir;

    @Bean
    public LedgerStateMachine ledgerStateMachine(PostgresEventStore postgresEventStore,
                                                 ObjectMapper objectMapper,
                                                 HybridLogicalClock hlc,
                                                 IdempotencyStore idempotencyStore) {
        log.info("Creating LedgerStateMachine");
        return new LedgerStateMachine(postgresEventStore, objectMapper, hlc, idempotencyStore);
    }

    @Bean
    public RaftGroup raftGroup() {
        // Parse peers from config: "node-1:ledger-1:9864,node-2:ledger-2:9865,..."
        List<RaftPeer> peers = Arrays.stream(peersConfig.split(","))
                .map(String::trim)
                .map(p -> {
                    String[] parts = p.split(":");
                    if (parts.length != 3) {
                        throw new IllegalArgumentException(
                                "Invalid peer format: '" + p + "'. Expected 'id:host:port'");
                    }
                    String peerId = parts[0];
                    String host = parts[1];
                    int port = Integer.parseInt(parts[2]);

                    return RaftPeer.newBuilder()
                            .setId(RaftPeerId.valueOf(peerId))
                            .setAddress(host + ":" + port)
                            .build();
                })
                .toList();

        UUID gid = UUID.nameUUIDFromBytes(groupId.getBytes());
        RaftGroup group = RaftGroup.valueOf(RaftGroupId.valueOf(gid), peers);

        log.info("Raft group configured: groupId={}, peers={}",
                groupId, peers.stream().map(p -> p.getId().toString()).toList());

        return group;
    }
    @Bean(destroyMethod = "close")
    public RaftServer raftServer(RaftGroup group,
                                 LedgerStateMachine stateMachine) throws IOException {
        RaftPeerId myId = RaftPeerId.valueOf(nodeId);

        RaftProperties properties = new RaftProperties();

        // Election timeout: 1000-2000ms for Docker networking
        // Default 150-300ms causes spurious elections in containers
        RaftServerConfigKeys.Rpc.setTimeoutMin(properties,
                TimeDuration.valueOf(1000, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(properties,
                TimeDuration.valueOf(2000, TimeUnit.MILLISECONDS));

        // gRPC transport
        GrpcConfigKeys.Server.setPort(properties, nodePort);

        // Storage directory for Raft WAL (write-ahead log)
        File raftStorageDir = new File(storageDir, nodeId);
        // If Raft storage already exists from a previous run, clean it.
        // This is safe because ChaosLedger's state lives in PostgreSQL —
        // the Raft WAL is only used for replication, not as the source of truth.
        // On restart, the node rejoins the cluster and catches up automatically.
        if (raftStorageDir.exists() && raftStorageDir.isDirectory()) {
            log.warn("Existing Raft storage found at {}. Cleaning for fresh FORMAT.",
                    raftStorageDir.getAbsolutePath());
            deleteRecursive(raftStorageDir);
        }
        RaftServerConfigKeys.setStorageDir(properties,
                Collections.singletonList(raftStorageDir));

        properties.set("raft.server.storage.startup.option", "RECOVER");

        log.info("Starting RaftServer: nodeId={}, address={}:{}, storage={}",
                nodeId, nodeHost, nodePort, raftStorageDir.getAbsolutePath());

        RaftServer server = RaftServer.newBuilder()
                .setServerId(myId)
                .setGroup(group)
                .setProperties(properties)
                .setStateMachineRegistry(gid -> stateMachine)
                .build();

        server.start();
        log.info("RaftServer started: {}", nodeId);

        return server;

    }

    @Bean(destroyMethod = "close")
    public RaftClient raftClient(RaftGroup group) throws IOException {
        RaftProperties properties = new RaftProperties();

        RaftClient client = RaftClient.newBuilder()
                .setRaftGroup(group)
                .setProperties(properties)
                .build();

        log.info("RaftClient created for group: {}", groupId);
        return client;
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            log.warn("Failed to delete: {}", file.getAbsolutePath());
        }
    }
}