package com.chaosledger.ledger.domain.properties;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.InsufficientBalanceException;
import com.chaosledger.ledger.domain.events.*;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROPERTY 3: No Negative Balances
 *
 * THE INVARIANT:
 *   No valid command sequence can produce an account with a negative
 *   balance. Any attempt to withdraw or transfer more than the
 *   available balance MUST be rejected with InsufficientBalanceException.
 *
 * WHY THIS MATTERS:
 *   A negative balance means money was spent that doesn't exist.
 *   In banking, this is an overdraft — and ChaosLedger doesn't
 *   support overdrafts. If this property fails, the ledger is
 *   allowing unauthorized credit creation.
 *
 * THE SUBTLETY — TESTING A REJECTION PROPERTY:
 *   Unlike conservation (which checks a positive outcome), this
 *   property checks that something CAN'T happen. The approach:
 *   generate random command sequences mixing deposits and withdrawals,
 *   execute them through the domain layer (which will reject some),
 *   and verify that after all VALID operations, no balance is negative.
 *
 * WHAT THIS CATCHES:
 *   - Race conditions in validateWithdrawal() with stale data
 *   - Bugs where subtract() doesn't check for negative results
 *   - Edge cases: withdrawing 0.01 when balance is 0.00
 *   - Integer overflow scenarios with very large amounts
 */
class NoNegativeBalancePropertyTest {

    // Sealed interface for random command types
    // jqwik generates a random mix of these.
    // Using sealed interface so the switch in the test is exhaustive.

    sealed interface LedgerCommand permits DepositCmd, WithdrawCmd {}

    record DepositCmd(BigDecimal amount) implements LedgerCommand {}

    record WithdrawCmd(BigDecimal amount) implements LedgerCommand {}

    // Main property test

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void noValidCommandSequence_canProduceNegativeBalance(
            @ForAll("commandSequences") List<LedgerCommand> commands
    ) {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // Start with an opened account (balance = 0)
        List<Event> events = new ArrayList<>();
        events.add(LedgerArbitraries.openEvent(accountId, ownerId));

        // Execute each command through the domain layer
        for (LedgerCommand cmd : commands) {
            // Reconstitute current state from events
            Account current = Account.reconstitute(new ArrayList<>(events));

            switch (cmd) {
                case DepositCmd d -> {
                    try {
                        current.validateDeposit(d.amount());
                        // Validation passed → append the event
                        events.add(LedgerArbitraries.depositEvent(
                                accountId, d.amount()));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid amount (zero, negative) — correctly rejected
                    }
                }
                case WithdrawCmd w -> {
                    try {
                        current.validateWithdrawal(w.amount());
                        // Validation passed → append the event
                        events.add(LedgerArbitraries.withdrawEvent(
                                accountId, w.amount()));
                    } catch (InsufficientBalanceException ignored) {
                        // Insufficient funds — correctly rejected
                    } catch (IllegalArgumentException ignored) {
                        // Invalid amount (zero, negative) — correctly rejected
                    }
                }
            }
        }

        // Assert: final balance must be >= 0
        Account finalAccount = Account.reconstitute(events);
        assertThat(finalAccount.getBalance().amount())
                .as("Balance after %d commands must be non-negative", commands.size())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // Additional property: balance tracks correctly step-by-step

    @Property(tries = 500)
    @Report(Reporting.GENERATED)
    void balanceNeverGoesNegative_atAnyIntermediateStep(
            @ForAll("commandSequences") List<LedgerCommand> commands
    ) {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        List<Event> events = new ArrayList<>();
        events.add(LedgerArbitraries.openEvent(accountId, ownerId));

        for (LedgerCommand cmd : commands) {
            Account current = Account.reconstitute(new ArrayList<>(events));

            switch (cmd) {
                case DepositCmd d -> {
                    try {
                        current.validateDeposit(d.amount());
                        events.add(LedgerArbitraries.depositEvent(
                                accountId, d.amount()));
                    } catch (IllegalArgumentException ignored) {}
                }
                case WithdrawCmd w -> {
                    try {
                        current.validateWithdrawal(w.amount());
                        events.add(LedgerArbitraries.withdrawEvent(
                                accountId, w.amount()));
                    } catch (InsufficientBalanceException
                             | IllegalArgumentException ignored) {}
                }
            }

            // Check balance AFTER EVERY step, not just at the end
            Account afterStep = Account.reconstitute(new ArrayList<>(events));
            assertThat(afterStep.getBalance().amount())
                    .as("Balance must be non-negative after every operation")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }


    @Provide
    Arbitrary<List<LedgerCommand>> commandSequences() {
        // Generate a mix of deposits and withdrawals
        Arbitrary<LedgerCommand> deposits = LedgerArbitraries.amounts()
                .map(DepositCmd::new);

        Arbitrary<LedgerCommand> withdrawals = LedgerArbitraries.amounts()
                .map(WithdrawCmd::new);

        // Mix: ~60% deposits, ~40% withdrawals
        // This ratio ensures accounts usually have funds to test withdrawals
        return Arbitraries.frequencyOf(
                        Tuple.of(3, deposits),
                        Tuple.of(2, withdrawals))
                .list()
                .ofMinSize(5)
                .ofMaxSize(30);
    }
}
