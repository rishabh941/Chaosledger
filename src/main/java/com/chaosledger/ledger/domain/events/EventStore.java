package com.chaosledger.ledger.domain.events;

import java.util.List;
import java.util.UUID;

/**
 * The append-only event log.
 *
 * This interface lives in the domain because the domain depends on
 * "we can save and load events", but does not care about HOW.
 * The actual implementation (Postgres) lives in infrastructure.
 */
public interface EventStore {

    /**
     * Append a list of events for a single aggregate.
     *
     * @param aggregateId the aggregate these events belong to
     * @param expectedVersion the version of the aggregate the caller observed.
     *                        If the actual stored version differs, throws
     *                        ConcurrencyException. Use 0 for a brand-new aggregate.
     * @param newEvents the events to append, in order
     * @throws ConcurrencyException if another writer modified the aggregate
     *                              since the caller loaded it
     */
    void append(UUID aggregateId, long expectedVersion, List<Event> newEvents);

    /**
     * Load all events for an aggregate, in version order.
     * Returns an empty list if the aggregate has no events yet.
     */
    List<Event> loadEvents(UUID aggregateId);

    /**
     * Find the current (highest) version of an aggregate.
     * Returns 0 if the aggregate has no events yet.
     */
    long currentVersion(UUID aggregateId);
}