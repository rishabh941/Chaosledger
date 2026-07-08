package com.chaosledger.ledger.infrastructure.raft;

import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
import com.chaosledger.ledger.infrastructure.eventstore.PostgresEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Raft-backed EventStore adapter.
 *
 * Replaces PostgresEventStore as the PRIMARY EventStore when
 * raft.enabled=true. Writes go through Raft consensus; reads go
 * directly to local PostgreSQL.
 *
 * The AccountCommandHandler still calls eventStore.append() exactly
 * as before — it does not know this adapter exists. Spring injects
 * this one instead because of @Primary.
 *
 * When raft.enabled is false (or absent), this bean is not created,
 * and Spring falls back to PostgresEventStore. All existing tests
 * continue to work in single-node mode.
 *
 * Serialization strategy:
 * Each Event is individually serialized to JSON along with its class
 * name (e.g. "AccountOpened"). This mirrors how PostgresEventStore
 * stores events in the database — using Class.forName() to deserialize
 * back to the concrete type. This avoids polluting the domain Event
 * interface with @JsonTypeInfo annotations.
 */
@Service
@Primary
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
@Slf4j
public class RaftEventStore implements EventStore {

    private final RaftClient raftClient;
    private final PostgresEventStore postgresEventStore;
    private final ObjectMapper objectMapper;

    public RaftEventStore(RaftClient raftClient,
                          PostgresEventStore postgresEventStore,
                          ObjectMapper objectMapper) {
        this.raftClient = raftClient;
        this.postgresEventStore = postgresEventStore;
        this.objectMapper = objectMapper;
        log.info("RaftEventStore initialized — writes will go through Raft consensus");
    }

    /**
     * Submit events to the Raft log and BLOCK until committed.
     *
     * The flow:
     * 1. Wrap each Event with its class name + serialized JSON
     * 2. Pack into a RaftEventCommand envelope
     * 3. Serialize the envelope to bytes
     * 4. Submit to Raft via RaftClient (blocking)
     * 5. Raft replicates to majority, then calls
     *    LedgerStateMachine.applyTransaction() on each node,
     *    which reconstructs the Events and writes to PostgreSQL
     * 6. This method returns only after Raft confirms commitment
     *
     * The REST endpoint receives HTTP 200 only after this returns.
     * If the leader crashes before commitment, the client gets an
     * exception and should retry. If it crashes after commitment,
     * the write is safe on the majority.
     */
    @Override
    public void append(UUID aggregateId, long expectedVersion,
                       List<Event> newEvents) {
        if (newEvents == null || newEvents.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot append empty event list");
        }

        try {
            // 1. Wrap each event with its type name + JSON
            List<RaftEventCommand.RaftEventEntry> entries = newEvents.stream()
                    .map(event -> {
                        try {
                            String typeName = event.getClass().getSimpleName();
                            String json = objectMapper.writeValueAsString(event);
                            return new RaftEventCommand.RaftEventEntry(typeName, json);
                        } catch (Exception e) {
                            throw new RuntimeException(
                                    "Failed to serialize event: "
                                            + event.getClass().getSimpleName(), e);
                        }
                    })
                    .toList();

            // 2. Create the command envelope
            RaftEventCommand cmd = new RaftEventCommand(
                    aggregateId, expectedVersion, entries);

            // 3. Serialize the entire envelope to bytes
            byte[] bytes = objectMapper.writeValueAsBytes(cmd);

            log.debug("Submitting to Raft: aggregateId={}, "
                            + "expectedVersion={}, events={}",
                    aggregateId, expectedVersion, newEvents.size());

            // 4. Submit to Raft — THIS BLOCKS until committed
            RaftClientReply reply = raftClient.io().send(
                    Message.valueOf(ByteString.copyFrom(bytes)));

            // 5. Check result
            if (!reply.isSuccess()) {
                String errorMsg = reply.getException() != null
                        ? reply.getException().getMessage()
                        : "Unknown Raft error";
                log.error("Raft commit failed for aggregate {}: {}",
                        aggregateId, errorMsg);
                throw new RuntimeException(
                        "Raft commit failed: " + errorMsg);
            }

            log.info("Raft committed: aggregateId={}, events={}",
                    aggregateId, newEvents.size());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error submitting to Raft for aggregate {}: {}",
                    aggregateId, e.getMessage(), e);
            throw new RuntimeException("Failed to submit to Raft", e);
        }
    }

    /**
     * Reads go directly to local PostgreSQL — no Raft consensus needed.
     * Each node's Postgres has all committed data.
     */
    @Override
    public List<Event> loadEvents(UUID aggregateId) {
        return postgresEventStore.loadEvents(aggregateId);
    }

    /**
     * Reads go directly to local PostgreSQL — no Raft consensus needed.
     */
    @Override
    public long currentVersion(UUID aggregateId) {
        return postgresEventStore.currentVersion(aggregateId);
    }
}