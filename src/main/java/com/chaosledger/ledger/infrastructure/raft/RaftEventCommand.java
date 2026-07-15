package com.chaosledger.ledger.infrastructure.raft;

import java.util.List;
import java.util.UUID;

/**
 * Carries an append() call through the Raft log.
 * Each event is wrapped with its type name so we can
 * deserialize without @JsonTypeInfo on Event.java.
 *
 * Week 9: Now includes the leader's HLC timestamp so all nodes
 * store the same causal timestamp for each committed entry.
 */
public record RaftEventCommand(
        UUID aggregateId,
        long expectedVersion,
        List<RaftEventEntry> entries,
        // Week 9 — HLC timestamp assigned by the leader before Raft submission
        Long hlcPhysicalTime,
        Integer hlcLogicalCounter,
        String hlcNodeId
) {
    /**
     * Wraps a serialized event with its concrete class name,
     * mirroring how PostgresEventStore stores event_type.
     */
    public record RaftEventEntry(
            String eventType,   // e.g. "AccountOpened"
            String eventJson    // the serialized JSON of the event
    ) {}
}