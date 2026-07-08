package com.chaosledger.ledger.domain.invariants;

import com.chaosledger.ledger.domain.events.AccountOpened;
import com.chaosledger.ledger.domain.events.Event;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INVARIANT: Account Integrity
 *
 * Every aggregate's first event must be AccountOpened.
 * No financial operations (deposits, withdrawals, transfers)
 * should exist for an account that was never opened.
 *
 * Why this matters:
 *   If money appears in an account without an AccountOpened event,
 *   the audit trail is broken. Regulators require every account
 *   to have a documented creation event.
 *
 * What it catches:
 *   - CommandHandler bypass: someone writing events directly to the
 *     database without going through openAccount first
 *   - Event store corruption: AccountOpened event deleted or lost
 *   - Deserialization bugs: AccountOpened stored but deserialized
 *     as a different event type
 */
public class AccountIntegrityInvariant implements Invariant {

    @Override
    public String name() {
        return "account-integrity";
    }

    @Override
    public String description() {
        return "Every aggregate starts with AccountOpened as its first event";
    }

    @Override
    public InvariantResult check(Map<UUID, List<Event>> eventsByAggregate) {
        Instant start = Instant.now();

        try {
            for (Map.Entry<UUID, List<Event>> entry : eventsByAggregate.entrySet()) {
                UUID aggregateId = entry.getKey();
                List<Event> events = entry.getValue();

                if (events.isEmpty()) {
                    return InvariantResult.failed(name(),
                            "Aggregate " + aggregateId + " has no events",
                            Duration.between(start, Instant.now()));
                }

                Event firstEvent = events.get(0);

                if (!(firstEvent instanceof AccountOpened)) {
                    return InvariantResult.failed(name(),
                            "Aggregate " + aggregateId + " starts with "
                                    + firstEvent.getClass().getSimpleName()
                                    + " instead of AccountOpened",
                            Duration.between(start, Instant.now()));
                }

                // Verify the AccountOpened event references the correct aggregate
                AccountOpened opened = (AccountOpened) firstEvent;
                if (!opened.aggregateId().equals(aggregateId)) {
                    return InvariantResult.failed(name(),
                            "AccountOpened for aggregate " + aggregateId
                                    + " references wrong aggregateId: " + opened.aggregateId(),
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