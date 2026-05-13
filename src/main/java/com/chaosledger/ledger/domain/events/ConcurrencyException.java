package com.chaosledger.ledger.domain.events;

import java.util.UUID;

/**
 * Thrown when an event append fails due to a concurrent modification
 * to the same aggregate. Caller should reload events and retry.
 */
public class ConcurrencyException extends RuntimeException {

    private final UUID aggregateId;
    private final long expectedVersion;

    public ConcurrencyException(UUID aggregateId, long expectedVersion) {
        super("Concurrent modification detected for aggregate " + aggregateId
                + " at expected version " + expectedVersion);
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
    }

    public UUID getAggregateId() { return aggregateId; }
    public long getExpectedVersion() { return expectedVersion; }
}