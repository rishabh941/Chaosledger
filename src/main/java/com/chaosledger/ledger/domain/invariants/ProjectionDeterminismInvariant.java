package com.chaosledger.ledger.domain.invariants;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.events.Event;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INVARIANT: Projection Determinism
 *
 * Replaying the same event sequence twice must produce the
 * exact same account state. This is a foundational requirement
 * of event sourcing: if replay isn't deterministic, you can't
 * trust reconstituted state.
 *
 * Why this matters:
 *   In Phase 3 (distribution), followers will rebuild state by
 *   replaying the leader's event log. If replay produces different
 *   results on different runs, followers will diverge from the
 *   leader silently — the worst kind of bug.
 *
 * What it catches:
 *   - System clock usage in apply() logic (non-deterministic)
 *   - Random number usage in apply() logic
 *   - Mutable static state that leaks between replays
 *   - Floating-point arithmetic (if someone accidentally uses
 *     double instead of BigDecimal)
 *
 * How it works:
 *   For each aggregate, reconstitute the account from its events
 *   twice (with fresh Account objects each time). Compare the
 *   resulting balances, versions, and statuses. Any difference
 *   means replay is non-deterministic.
 */
public class ProjectionDeterminismInvariant implements Invariant {

    @Override
    public String name() {
        return "projection-determinism";
    }

    @Override
    public String description() {
        return "Replaying the same events twice produces identical account state";
    }

    @Override
    public InvariantResult check(Map<UUID, List<Event>> eventsByAggregate) {
        Instant start = Instant.now();

        try {
            for (Map.Entry<UUID, List<Event>> entry : eventsByAggregate.entrySet()) {
                UUID aggregateId = entry.getKey();
                List<Event> events = entry.getValue();

                if (events.isEmpty()) continue;

                // First replay
                Account first = Account.reconstitute(new ArrayList<>(events));

                // Second replay (fresh object, same events)
                Account second = Account.reconstitute(new ArrayList<>(events));

                // Compare balance
                BigDecimal balance1 = first.getBalance().amount();
                BigDecimal balance2 = second.getBalance().amount();

                if (balance1.compareTo(balance2) != 0) {
                    return InvariantResult.failed(name(),
                            "Account " + aggregateId
                                    + " produced different balances on replay: "
                                    + balance1 + " vs " + balance2,
                            Duration.between(start, Instant.now()));
                }

                // Compare version
                if (first.getVersion() != second.getVersion()) {
                    return InvariantResult.failed(name(),
                            "Account " + aggregateId
                                    + " produced different versions on replay: "
                                    + first.getVersion() + " vs " + second.getVersion(),
                            Duration.between(start, Instant.now()));
                }

                // Compare status
                if (first.getStatus() != second.getStatus()) {
                    return InvariantResult.failed(name(),
                            "Account " + aggregateId
                                    + " produced different statuses on replay: "
                                    + first.getStatus() + " vs " + second.getStatus(),
                            Duration.between(start, Instant.now()));
                }

                // Compare currency
                if (!first.getCurrency().equals(second.getCurrency())) {
                    return InvariantResult.failed(name(),
                            "Account " + aggregateId
                                    + " produced different currencies on replay: "
                                    + first.getCurrency() + " vs " + second.getCurrency(),
                            Duration.between(start, Instant.now()));
                }
            }

            return InvariantResult.passed(name(), Duration.between(start, Instant.now()));

        } catch (Exception e) {
            return InvariantResult.error(name(),
                    "Check failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        }
    }
}