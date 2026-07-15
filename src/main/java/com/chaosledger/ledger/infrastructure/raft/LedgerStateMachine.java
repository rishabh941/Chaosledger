package com.chaosledger.ledger.infrastructure.raft;

import com.chaosledger.ledger.domain.events.ConcurrencyException;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.hlc.HlcTimestamp;
import com.chaosledger.ledger.domain.hlc.HybridLogicalClock;
import com.chaosledger.ledger.infrastructure.eventstore.PostgresEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The bridge between Raft and ChaosLedger's domain.
 *
 * When Raft commits a log entry (majority of nodes have written it),
 * it calls applyTransaction() on every node's state machine.
 * This class deserializes the committed entry back into domain events
 * and writes them to PostgreSQL via PostgresEventStore.
 *
 * Deserialization strategy:
 * Each event is stored in the Raft log as a (typeName, json) pair
 * inside a RaftEventCommand. The typeName (e.g. "AccountOpened") is
 * used with Class.forName() to find the concrete event class — the
 * same pattern PostgresEventStore.fromEntity() uses. This keeps the
 * domain Event interface free of Jackson annotations.
 *
 * CRITICAL: This method runs on ALL nodes (leader + followers).
 * Each node independently applies the same entries in the same order,
 * so all PostgreSQL databases end up with identical data.
 * Week 9 change: When applying a committed entry, the state machine now:
 *  1. Extracts the leader's HLC timestamp from the RaftEventCommand
 *  2. Calls hlc.update(leaderTimestamp) to advance the local clock
 *  3. Passes the HLC timestamp to PostgresEventStore.appendWithHlc()
 * This ensures every node's HLC stays causally consistent with the leader,
 * even if the follower's wall clock is behind.
 */
@Slf4j
public class LedgerStateMachine extends BaseStateMachine {

    private static final String EVENT_PACKAGE =
            "com.chaosledger.ledger.domain.events.";

    private final PostgresEventStore postgresEventStore;
    private final ObjectMapper objectMapper;
    private final HybridLogicalClock hlc;

    public LedgerStateMachine(PostgresEventStore postgresEventStore,
                              ObjectMapper objectMapper,
                              HybridLogicalClock hlc) {
        this.postgresEventStore = postgresEventStore;
        this.objectMapper = objectMapper;
        this.hlc = hlc;
    }

    /**
     * Called when a Raft log entry is COMMITTED (majority has it).
     * Runs on ALL nodes — leader AND followers.
     * Each node applies the same entries in the same order,
     * so all PostgreSQL databases end up identical.
     */
    @Override
    public CompletableFuture<Message> applyTransaction(
            TransactionContext trx) {

        LogEntryProto entry = trx.getLogEntry();
        TermIndex termIndex = TermIndex.valueOf(entry);

        try {
            // 1. Extract serialized command from the log entry
            ByteString logData = entry.getStateMachineLogEntry()
                    .getLogData();
            byte[] bytes = logData.toByteArray();

            // 2. Deserialize the envelope
            RaftEventCommand cmd = objectMapper.readValue(
                    bytes, RaftEventCommand.class);

            // Week 9: Extract HLC timestamp from the command
            HlcTimestamp leaderHlc = null;
            if (cmd.hlcPhysicalTime() != null
                    && cmd.hlcLogicalCounter() != null
                    && cmd.hlcNodeId() != null) {
                leaderHlc = new HlcTimestamp(
                        cmd.hlcPhysicalTime(),
                        cmd.hlcLogicalCounter(),
                        cmd.hlcNodeId());

                // Update local HLC — ensures this node's clock
                // stays >= the leader's timestamp
                hlc.update(leaderHlc);
                log.debug("HLC updated from leader timestamp: {}", leaderHlc);
            }

            // 3. Reconstruct Event objects from type name + JSON
            //    Same pattern as PostgresEventStore.fromEntity()
            List<Event> events = cmd.entries().stream()
                    .map(this::deserializeEvent)
                    .toList();

            log.info("Applying committed entry [term={}, index={}]: "
                            + "aggregateId={}, expectedVersion={}, eventCount={}, hlc{}",
                    termIndex.getTerm(), termIndex.getIndex(),
                    cmd.aggregateId(), cmd.expectedVersion(),
                    events.size(), leaderHlc);

            // Week 9: Use appendWithHlc if HLC timestamp is present
            if (leaderHlc != null) {
                postgresEventStore.appendWithHlc(
                        cmd.aggregateId(),
                        cmd.expectedVersion(),
                        events,
                        leaderHlc);
            } else {
                // Backward compatibility for entries committed
                // before Week 9 (no HLC in the log)
                postgresEventStore.append(
                        cmd.aggregateId(),
                        cmd.expectedVersion(),
                        events);
            }

            log.debug("Successfully applied entry [index={}] to Postgres",
                    termIndex.getIndex());

            return CompletableFuture.completedFuture(
                    Message.valueOf("OK"));

        } catch (ConcurrencyException e) {
            // This can happen if the state machine replays entries
            // after a restart. The events are already in Postgres
            // from a previous apply — safe to skip.
            log.warn("ConcurrencyException applying entry [index={}]: {}. "
                            + "This may be a replay after restart — already applied.",
                    termIndex.getIndex(), e.getMessage());
            return CompletableFuture.completedFuture(
                    Message.valueOf("ALREADY_APPLIED"));

        } catch (Exception e) {
            log.error("Failed to apply entry [index={}]: {}",
                    termIndex.getIndex(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Deserialize a single event from its type name and JSON string.
     * Uses Class.forName() — the same approach as PostgresEventStore.
     *
     * Example:
     *   eventType = "AccountOpened"
     *   eventJson = {"eventId":"...", "aggregateId":"...", ...}
     *   → resolves to com.chaosledger.ledger.domain.events.AccountOpened
     *   → Jackson deserializes the JSON into that class
     */
    private Event deserializeEvent(RaftEventCommand.RaftEventEntry entry) {
        try {
            String fqcn = EVENT_PACKAGE + entry.eventType();
            Class<?> clazz = Class.forName(fqcn);
            return (Event) objectMapper.readValue(entry.eventJson(), clazz);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Unknown event type: " + entry.eventType()
                            + ". Is the class in the domain.events package?", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to deserialize event of type "
                            + entry.eventType() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Called for read-only queries. Reads bypass Raft consensus —
     * they go directly to the local PostgreSQL via the REST
     * controllers and PostgresEventStore.loadEvents().
     */
    @Override
    public CompletableFuture<Message> query(Message request) {
        return CompletableFuture.completedFuture(
                Message.valueOf("OK"));
    }
}