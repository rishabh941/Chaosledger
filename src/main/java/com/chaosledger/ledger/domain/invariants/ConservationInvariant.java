package com.chaosledger.ledger.domain.invariants;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.events.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * INVARIANT: Conservation of Money
 *
 * The total money in the system must be constant. Specifically:
 *
 *   Sum of all account balances (from replay)
 *   ==
 *   Sum of all deposits - Sum of all withdrawals (from raw events)
 *
 * Transfers don't change the total — they move money between accounts.
 * Only deposits (money entering the system) and withdrawals (money
 * leaving the system) change the total.
 *
 * Why TWO calculations?
 *   Computing the total two independent ways is a cross-check.
 *   If Account.reconstitute() has a bug (e.g., a transfer debits
 *   the sender but the credit to the receiver is 1 cent less),
 *   the balance-sum will differ from the event-sum. This double
 *   calculation is the accounting equivalent of "trust, but verify."
 *
 * What it catches:
 *   - Transfer logic that creates or destroys money
 *   - BigDecimal rounding errors in Account.apply()
 *   - Off-by-one bugs in Money.add() or Money.subtract()
 *   - Missing TransferReceived events (debit recorded, credit lost)
 *
 * This is the single most important invariant in any financial system.
 */
public class ConservationInvariant implements Invariant {

    @Override
    public String name() {
        return "conservation-of-money";
    }

    @Override
    public String description() {
        return "Total money across all accounts equals total deposits minus total withdrawals";
    }

    @Override
    public InvariantResult check(Map<UUID, List<Event>> eventsByAggregate) {
        Instant start = Instant.now();

        try {
            // Method 1: Sum of balances from reconstituted accounts
            BigDecimal balanceSum = BigDecimal.ZERO;
            int accountCount = 0;

            for (Map.Entry<UUID, List<Event>> entry : eventsByAggregate.entrySet()) {
                List<Event> events = entry.getValue();
                if (events.isEmpty()) continue;

                Account account = Account.reconstitute(events);
                balanceSum = balanceSum.add(account.getBalance().amount());
                accountCount++;
            }

            // Method 2: Sum of deposits minus withdrawals from raw events
            // Transfers are INTERNAL moves — they don't change the total.
            // Only deposits (money in) and withdrawals (money out) matter.
            BigDecimal totalDeposits = BigDecimal.ZERO;
            BigDecimal totalWithdrawals = BigDecimal.ZERO;

            for (List<Event> events : eventsByAggregate.values()) {
                for (Event event : events) {
                    if (event instanceof MoneyDeposited deposited) {
                        totalDeposits = totalDeposits.add(deposited.amount());
                    } else if (event instanceof MoneyWithdrawn withdrawn) {
                        totalWithdrawals = totalWithdrawals.add(withdrawn.amount());
                    }
                    // MoneyTransferred and TransferReceived are internal —
                    // they cancel out across accounts and don't affect the total.
                }
            }

            BigDecimal expectedTotal = totalDeposits.subtract(totalWithdrawals);

            // Compare the two methods
            if (balanceSum.compareTo(expectedTotal) != 0) {
                return InvariantResult.failed(name(),
                        "Balance sum (" + balanceSum
                                + ") != deposits - withdrawals (" + expectedTotal
                                + "). Difference: " + balanceSum.subtract(expectedTotal)
                                + ". Accounts checked: " + accountCount,
                        Duration.between(start, Instant.now()));
            }

            return InvariantResult.passed(name(), Duration.between(start, Instant.now()));

        } catch (Exception e) {
            return InvariantResult.error(name(),
                    "Check failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        }
    }
}
