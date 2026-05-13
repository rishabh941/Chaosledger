package com.chaosledger.ledger.infrastructure.eventstore;

import com.chaosledger.ledger.domain.events.ConcurrencyException;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
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

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void append(UUID aggregateId, long expectedVersion, List<Event> newEvents) {
        if (newEvents == null || newEvents.isEmpty()) {
            throw new IllegalArgumentException("Cannot append empty event list");
        }

        // Verify expectedVersion matches what's actually stored.
        // This is the optimistic concurrency check.
        long actualVersion = currentVersion(aggregateId);
        if (actualVersion != expectedVersion) {
            log.warn("Concurrency conflict: expected version {} but actual is {} for aggregate {}",
                    expectedVersion, actualVersion, aggregateId);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        // Convert each domain event to a database entity, assigning sequential versions.
        long nextVersion = expectedVersion + 1;
        List<EventEntity> entities = newEvents.stream()
                .map(event -> toEntity(event, nextVersion))
                .collect(Collectors.toList());

        // Reassign sequential versions properly (the lambda above used a fixed nextVersion)
        for (int i = 0; i < entities.size(); i++) {
            EventEntity e = entities.get(i);
            entities.set(i, EventEntity.builder()
                    .id(e.getId())
                    .aggregateId(e.getAggregateId())
                    .aggregateType(e.getAggregateType())
                    .eventType(e.getEventType())
                    .payload(e.getPayload())
                    .version(expectedVersion + 1 + i)
                    .createdAt(e.getCreatedAt())
                    .build());
        }

        // Save. The database's UNIQUE (aggregate_id, version) constraint
        // is the actual atomic enforcer. If another writer raced ahead between
        // our check and our save, the database will reject our insert.
        try {
            repository.saveAll(entities);
            repository.flush();  // Force immediate write to detect constraint violations now, not later
        } catch (DataIntegrityViolationException ex) {
            log.warn("DB-level concurrency conflict on aggregate {} at expected version {}",
                    aggregateId, expectedVersion);
            throw new ConcurrencyException(aggregateId, expectedVersion);
        }

        log.debug("Appended {} events for aggregate {} at versions {}-{}",
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
            // Look up the class from the event_type column.
            // This requires events to be in a known package; we'll formalize this in Week 3.
            Class<?> eventClass = Class.forName(
                    "com.chaosledger.ledger.domain.events." + entity.getEventType());
            return (Event) objectMapper.treeToValue(entity.getPayload(), eventClass);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to deserialize event " + entity.getId() + " of type " + entity.getEventType(), ex);
        }
    }
}