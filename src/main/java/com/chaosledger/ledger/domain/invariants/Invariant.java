package com.chaosledger.ledger.domain.invariants;

import com.chaosledger.ledger.domain.events.Event;

import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * A live invariant that can be checked against the current event store state.
 *
 * Each invariant receives ALL events grouped by aggregate ID,
 * so it can rebuild account states and verify properties.
 *
 * Invariants are domain-layer ports. They know nothing about
 * scheduling, REST, or databases — only about events and accounts.
 */
public interface Invariant {

    /**
     * A short, unique name for this invariant (e.g., "conservation-of-money").
     * Used as the key in the REST response.
     */
    String name();

    /**
     * A human-readable description of what this invariant checks.
     */
    String description();

    /**
     * Run the check against the given events, grouped by aggregate ID.
     *
     * @param eventsByAggregate all events in the system, grouped by aggregate ID,
     *                          each list sorted by version ascending
     * @return the result of the check
     */
    InvariantResult check(Map<UUID, List<Event>> eventsByAggregate);
}