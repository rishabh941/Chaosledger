package com.chaosledger.ledger.domain.invariants;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.events.Event;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INVARIANT: No Negative Balance
 *
 * After replaying all events for every account, no account
 * should have a negative balance.
 *
 * Why this matters:
 *   A negative balance means the ledger allowed an overdraft
 *   that wasn't authorized. In a real bank, this means the
 *   institution is exposed to credit risk it didn't agree to.
 *
 * What it catches:
 *   - Withdrawal validation bypass (e.g., concurrent withdrawals
 *     where both pass the balance check)
 *   - BigDecimal comparison bugs (e.g., using equals() instead
 *     of compareTo(), which treats 10.00 != 10.0)
 *   - Transfer logic that debits the sender but applies the
 *     wrong amount
 *
 * How it works:
 *   Uses Account.reconstitute() — the exact same replay logic
 *   that the CommandHandler uses. If reconstitution shows a
 *   negative balance, the events in the store are inconsistent.
 */
public class NoNegativeBalanceInvariant implements Invariant {

    @Override
    public String name() {
        return "no-negative-balance";
    }

    @Override
    public String description() {
        return "No account has a negative balance after replaying all events";
    }

    @Override
    public InvariantResult check(Map<UUID, List<Event>> eventsByAggregate) {
        Instant start = Instant.now();

        try {
            for (Map.Entry<UUID, List<Event>> entry : eventsByAggregate.entrySet()) {
                UUID aggregateId = entry.getKey();
                List<Event> events = entry.getValue();

                if (events.isEmpty()) continue;

                Account account = Account.reconstitute(events);
                BigDecimal balance = account.getBalance().amount();

                if (balance.compareTo(BigDecimal.ZERO) < 0) {
                    return InvariantResult.failed(name(),
                            "Account " + aggregateId + " has negative balance: "
                                    + balance + " " + account.getCurrency(),
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