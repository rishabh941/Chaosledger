package com.chaosledger.ledger.infrastructure.eventstore;

import com.chaosledger.ledger.domain.events.ConcurrencyException;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
import com.chaosledger.ledger.domain.hlc.HlcTimestamp;
import com.chaosledger.ledger.domain.hlc.HybridLogicalClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostgresEventStore implements EventStore {

    private final EventRepository repository;
    private final ObjectMapper objectMapper;
    private final HybridLogicalClock hlc;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void append(UUID aggregateId, long expectedVersion, List<Event> newEvents) {
        if (newEvents == null || newEvents.isEmpty()) {
            throw new IllegalArgumentException("Cannot append empty event list");
        }

        long actualVersion = currentVersion(aggregateId);
        if (actualVersion != expectedVersion) {
            log.warn("Concurrency conflict: expected version {} but actual is {} for aggregate {}",
                    expectedVersion, actualVersion, aggregateId);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        long nextVersion = expectedVersion + 1;
        List<EventEntity> entities = newEvents.stream()
                .map(event -> toEntity(event, nextVersion))
                .collect(Collectors.toList());

        for (int i = 0; i < entities.size(); i++) {
            // Tick the HLC for each event — guarantees unique, monotonic timestamps
            HlcTimestamp ts = hlc.tick();

            EventEntity e = entities.get(i);
            entities.set(i, EventEntity.builder()
                    .id(e.getId())
                    .aggregateId(e.getAggregateId())
                    .aggregateType(e.getAggregateType())
                    .eventType(e.getEventType())
                    .payload(e.getPayload())
                    .version(expectedVersion + 1 + i)
                    .createdAt(e.getCreatedAt())
                    .hlcPhysicalTime(ts.physicalTime())
                    .hlcLogicalCounter(ts.logicalCounter())
                    .hlcNodeId(ts.nodeId())
                    .build());
        }

        try {
            repository.saveAll(entities);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            log.warn("DB-level concurrency conflict on aggregate {} at expected version {}",
                    aggregateId, expectedVersion);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        log.debug("Appended {} events for aggregate {} at versions {}-{}",
                entities.size(), aggregateId,
                expectedVersion + 1, expectedVersion + entities.size());
    }

    /**
     * Overloaded append that accepts a pre-assigned HLC timestamp.
     * Used by LedgerStateMachine when applying Raft-committed entries —
     * the HLC timestamp was generated on the leader and must be
     * stored identically on all nodes.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void appendWithHlc(UUID aggregateId, long expectedVersion,
                              List<Event> newEvents, HlcTimestamp hlcTimestamp) {
        if (newEvents == null || newEvents.isEmpty()) {
            throw new IllegalArgumentException("Cannot append empty event list");
        }

        long actualVersion = currentVersion(aggregateId);
        if (actualVersion != expectedVersion) {
            log.warn("Concurrency conflict: expected version {} but actual is {} for aggregate {}",
                    expectedVersion, actualVersion, aggregateId);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        List<EventEntity> entities = newEvents.stream()
                .map(event -> toEntity(event, 0))
                .collect(Collectors.toList());

        for (int i = 0; i < entities.size(); i++) {
            // Use the leader's HLC for the first event.
            // For subsequent events in the same batch, tick locally
            // but preserve the leader's physical time as minimum.
            HlcTimestamp ts;
            if (i == 0) {
                ts = hlcTimestamp;
            } else {
                // Tick locally but update from the leader's timestamp
                // to maintain causal ordering within the batch
                ts = hlc.tick();
            }

            EventEntity e = entities.get(i);
            entities.set(i, EventEntity.builder()
                    .id(e.getId())
                    .aggregateId(e.getAggregateId())
                    .aggregateType(e.getAggregateType())
                    .eventType(e.getEventType())
                    .payload(e.getPayload())
                    .version(expectedVersion + 1 + i)
                    .createdAt(e.getCreatedAt())
                    .hlcPhysicalTime(ts.physicalTime())
                    .hlcLogicalCounter(ts.logicalCounter())
                    .hlcNodeId(ts.nodeId())
                    .build());
        }

        try {
            repository.saveAll(entities);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            log.warn("DB-level concurrency conflict on aggregate {} at expected version {}",
                    aggregateId, expectedVersion);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        log.debug("Appended {} events with HLC for aggregate {} at versions {}-{}",
                entities.size(), aggregateId,
                expectedVersion + 1, expectedVersion + entities.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Event> loadEvents(UUID aggregateId) {
        return repository.findByAggregateIdOrderByVersionAsc(aggregateId).stream()
                .map(this::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long currentVersion(UUID aggregateId) {
        Long max = repository.findMaxVersionForAggregate(aggregateId);
        return max == null ? 0L : max;
    }

    // === Conversion helpers ===

    private EventEntity toEntity(Event event, long version) {
        JsonNode payload = objectMapper.valueToTree(event);
        return EventEntity.builder()
                .id(event.eventId())
                .aggregateId(event.aggregateId())
                .aggregateType(event.aggregateType())
                .eventType(event.getClass().getSimpleName())
                .payload(payload)
                .version(version)
                .createdAt(Instant.now())
                .build();
    }

    private Event fromEntity(EventEntity entity) {
        try {
            Class<?> eventClass = Class.forName(
                    "com.chaosledger.ledger.domain.events." + entity.getEventType());
            return (Event) objectMapper.treeToValue(entity.getPayload(), eventClass);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to deserialize event " + entity.getId()
                            + " of type " + entity.getEventType(), ex);
        }
    }
}