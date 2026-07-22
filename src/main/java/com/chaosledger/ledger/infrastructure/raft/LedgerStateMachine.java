package com.chaosledger.ledger.infrastructure.raft;


import com.chaosledger.ledger.domain.IdempotencyStore;
import com.chaosledger.ledger.domain.events.ConcurrencyException;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.MoneyDeposited;
import com.chaosledger.ledger.domain.events.MoneyTransferred;
import com.chaosledger.ledger.domain.events.MoneyWithdrawn;
import com.chaosledger.ledger.domain.events.TransferReceived;
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
import com.chaosledger.ledger.infrastructure.kafka.KafkaEventPublisher;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The bridge between Raft and ChaosLedger's domain.
 *
 * When Raft commits a log entry (majority of nodes have written it),
 * it calls applyTransaction() on every node's state machine.
 * This class deserializes the committed entry back into domain events
 * and writes them to PostgreSQL via PostgresEventStore.
 *
 * Week 12 fix: After persisting events, the state machine now also
 * records idempotency keys in processed_commands. This ensures all
 * nodes have the same idempotency state — not just the leader that
 * originally handled the command. Without this, a retry after leader
 * failover would bypass the idempotency check and apply the write
 * twice (Bug #X — found by chaos scenario 7.3).
 */
@Slf4j
public class LedgerStateMachine extends BaseStateMachine {

    private static final String EVENT_PACKAGE =
            "com.chaosledger.ledger.domain.events.";

    private final PostgresEventStore postgresEventStore;
    private final ObjectMapper objectMapper;
    private final HybridLogicalClock hlc;
    private final IdempotencyStore idempotencyStore;
    private final KafkaEventPublisher kafkaPublisher;

    public LedgerStateMachine(PostgresEventStore postgresEventStore,
                              ObjectMapper objectMapper,
                              HybridLogicalClock hlc,
                              IdempotencyStore idempotencyStore,
                              KafkaEventPublisher kafkaPublisher) {
        this.postgresEventStore = postgresEventStore;
        this.objectMapper = objectMapper;
        this.hlc = hlc;
        this.idempotencyStore = idempotencyStore;
        this.kafkaPublisher = kafkaPublisher;
    }

    @Override
    public CompletableFuture<Message> applyTransaction(
            TransactionContext trx) {

        LogEntryProto entry = trx.getLogEntry();
        TermIndex termIndex = TermIndex.valueOf(entry);

        try {
            ByteString logData = entry.getStateMachineLogEntry()
                    .getLogData();
            byte[] bytes = logData.toByteArray();

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

                hlc.update(leaderHlc);
                log.debug("HLC updated from leader timestamp: {}", leaderHlc);
            }

            List<Event> events = cmd.entries().stream()
                    .map(this::deserializeEvent)
                    .toList();

            log.info("Applying committed entry [term={}, index={}]: "
                            + "aggregateId={}, expectedVersion={}, eventCount={}, hlc={}",
                    termIndex.getTerm(), termIndex.getIndex(),
                    cmd.aggregateId(), cmd.expectedVersion(),
                    events.size(), leaderHlc);

            if (leaderHlc != null) {
                postgresEventStore.appendWithHlc(
                        cmd.aggregateId(),
                        cmd.expectedVersion(),
                        events,
                        leaderHlc);
            } else {
                postgresEventStore.append(
                        cmd.aggregateId(),
                        cmd.expectedVersion(),
                        events);
            }

            // ── Week 12 fix: replicate idempotency keys ────────
            // Record idempotency keys from committed events so ALL
            // nodes (not just the leader) have them. This prevents
            // duplicate writes after leader failover.
            recordIdempotencyKeys(events, cmd.aggregateId());

            log.debug("Successfully applied entry [index={}] to Postgres",
                    termIndex.getIndex());

            return CompletableFuture.completedFuture(
                    Message.valueOf("OK"));

        } catch (ConcurrencyException e) {
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
     * Extract idempotency keys from committed events and record them
     * in processed_commands. Uses recordIfAbsent to handle:
     *   - Leader: key already recorded by AccountCommandHandler
     *   - Followers: key not yet recorded, needs to be added
     *   - Replays after restart: key already exists, skip safely
     */
    private void recordIdempotencyKeys(List<Event> events, UUID aggregateId) {
        for (Event event : events) {
            try {
                UUID key = extractIdempotencyKey(event);
                String type = extractCommandType(event);
                if (key != null) {
                    idempotencyStore.recordIfAbsent(key, type, aggregateId);
                    log.debug("Recorded idempotency key {} for {} on this node",
                            key, type);
                }
            } catch (Exception e) {
                // Never fail the state machine apply for idempotency
                // recording — the event is already committed
                log.warn("Failed to record idempotency key from {}: {}",
                        event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private UUID extractIdempotencyKey(Event event) {
        return switch (event) {
            case MoneyDeposited e -> e.idempotencyKey();
            case MoneyWithdrawn e -> e.idempotencyKey();
            case MoneyTransferred e -> e.idempotencyKey();
            case TransferReceived e -> e.idempotencyKey();
            default -> null;
        };
    }

    private String extractCommandType(Event event) {
        return switch (event) {
            case MoneyDeposited ignored -> "Deposit";
            case MoneyWithdrawn ignored -> "Withdraw";
            case MoneyTransferred ignored -> "Transfer";
            case TransferReceived ignored -> "TransferReceived";
            default -> "Unknown";
        };
    }

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

    @Override
    public CompletableFuture<Message> query(Message request) {
        return CompletableFuture.completedFuture(
                Message.valueOf("OK"));
    }
}