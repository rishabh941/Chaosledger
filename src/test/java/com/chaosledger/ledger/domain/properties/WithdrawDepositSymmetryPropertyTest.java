package com.chaosledger.ledger.domain.properties;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.events.*;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROPERTY 5: Withdraw-Deposit Symmetry
 *
 * THE INVARIANT:
 *   For any account with sufficient funds, withdrawing X then
 *   depositing X returns the balance to its original value.
 *   This is the "there and back again" property pattern.
 *
 * WHY THIS MATTERS:
 *   Symmetry properties are powerful because they test inverse
 *   operations. If deposit and withdraw are truly inverse operations
 *   (as they should be in a correct ledger), then applying one
 *   followed by the other should be a no-op on balance.
 *
 *   If this fails, it means either:
 *   - deposit(X) doesn't add exactly X (rounding?)
 *   - withdraw(X) doesn't subtract exactly X (rounding?)
 *   - There's a side effect in one but not the other
 *   - The Money value object's add/subtract aren't true inverses
 *
 * THE PATTERN — "THERE AND BACK AGAIN":
 *   This is one of Scott Wlaschin's property patterns:
 *   apply(f) then apply(f_inverse) should return to start state.
 *   - serialize then deserialize → same object
 *   - encrypt then decrypt → same plaintext
 *   - withdraw then deposit → same balance
 *
 * WHAT THIS CATCHES:
 *   - Rounding asymmetry between add() and subtract()
 *   - Scale mismatches in BigDecimal operations
 *   - Hidden fees or rounding that only apply to one direction
 *   - Version counting bugs (version should increase by 2)
 */
class WithdrawDepositSymmetryPropertyTest {

    // Test 1: Basic symmetry — withdraw X then deposit X

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void withdrawThenDeposit_returnsToOriginalBalance(
            @ForAll("initialAmounts") BigDecimal initialDeposit,
            @ForAll("roundTripAmounts") BigDecimal roundTripAmount
    ) {
        // Filter: roundTripAmount must be <= initialDeposit
        // (can't withdraw more than you have)
        Assume.that(roundTripAmount.compareTo(initialDeposit) <= 0);

        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // Build initial state: open + deposit
        List<Event> events = new ArrayList<>();
        events.add(LedgerArbitraries.openEvent(accountId, ownerId));
        events.add(LedgerArbitraries.depositEvent(accountId, initialDeposit));

        Account before = Account.reconstitute(new ArrayList<>(events));
        BigDecimal balanceBefore = before.getBalance().amount();
        long versionBefore = before.getVersion();

        // Withdraw X
        events.add(LedgerArbitraries.withdrawEvent(accountId, roundTripAmount));

        // Deposit X back
        events.add(LedgerArbitraries.depositEvent(accountId, roundTripAmount));

        Account after = Account.reconstitute(events);

        // Assert: balance is exactly the same
        assertThat(after.getBalance().amount())
                .as("Balance after withdraw(%s) then deposit(%s) must equal original",
                        roundTripAmount, roundTripAmount)
                .isEqualByComparingTo(balanceBefore);

        // Assert: version increased by exactly 2
        assertThat(after.getVersion())
                .as("Version should increase by 2 (one withdraw + one deposit event)")
                .isEqualTo(versionBefore + 2);
    }

    // Test 2: Reverse symmetry — deposit X then withdraw X

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void depositThenWithdraw_returnsToOriginalBalance(
            @ForAll("roundTripAmounts") BigDecimal roundTripAmount
    ) {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // Start with zero balance
        List<Event> events = new ArrayList<>();
        events.add(LedgerArbitraries.openEvent(accountId, ownerId));

        Account before = Account.reconstitute(new ArrayList<>(events));
        BigDecimal balanceBefore = before.getBalance().amount();
        assertThat(balanceBefore).isEqualByComparingTo(BigDecimal.ZERO);

        // Deposit X
        events.add(LedgerArbitraries.depositEvent(accountId, roundTripAmount));

        // Withdraw X
        events.add(LedgerArbitraries.withdrawEvent(accountId, roundTripAmount));

        Account after = Account.reconstitute(events);

        // Balance should return to zero
        assertThat(after.getBalance().amount())
                .as("Deposit(%s) then Withdraw(%s) on zero balance → back to zero",
                        roundTripAmount, roundTripAmount)
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Test 3: Transfer symmetry — transfer A→B then B→A

    @Property(tries = 500)
    @Report(Reporting.GENERATED)
    void transferThenReverseTransfer_returnsToOriginalBalances(
            @ForAll("initialAmounts") BigDecimal depositA,
            @ForAll("initialAmounts") BigDecimal depositB,
            @ForAll("roundTripAmounts") BigDecimal transferAmount
    ) {
        // Transfer amount must be within sender A's balance
        Assume.that(transferAmount.compareTo(depositA) <= 0);

        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        // Setup accounts
        List<Event> eventsA = new ArrayList<>();
        eventsA.add(LedgerArbitraries.openEvent(accountA, owner));
        eventsA.add(LedgerArbitraries.depositEvent(accountA, depositA));

        List<Event> eventsB = new ArrayList<>();
        eventsB.add(LedgerArbitraries.openEvent(accountB, owner));
        eventsB.add(LedgerArbitraries.depositEvent(accountB, depositB));

        BigDecimal balanceA_before = Account.reconstitute(
                new ArrayList<>(eventsA)).getBalance().amount();
        BigDecimal balanceB_before = Account.reconstitute(
                new ArrayList<>(eventsB)).getBalance().amount();

        // Transfer A → B
        eventsA.add(LedgerArbitraries.transferEvent(
                accountA, accountB, transferAmount));
        eventsB.add(LedgerArbitraries.transferReceivedEvent(
                accountB, accountA, transferAmount));

        // Reverse: Transfer B → A
        eventsB.add(LedgerArbitraries.transferEvent(
                accountB, accountA, transferAmount));
        eventsA.add(LedgerArbitraries.transferReceivedEvent(
                accountA, accountB, transferAmount));

        // Assert: both balances back to original
        BigDecimal balanceA_after = Account.reconstitute(eventsA)
                .getBalance().amount();
        BigDecimal balanceB_after = Account.reconstitute(eventsB)
                .getBalance().amount();

        assertThat(balanceA_after)
                .as("Account A balance after transfer + reverse")
                .isEqualByComparingTo(balanceA_before);
        assertThat(balanceB_after)
                .as("Account B balance after transfer + reverse")
                .isEqualByComparingTo(balanceB_before);
    }


    @Provide
    Arbitrary<BigDecimal> initialAmounts() {
        return LedgerArbitraries.amounts();
    }

    @Provide
    Arbitrary<BigDecimal> roundTripAmounts() {
        return LedgerArbitraries.amounts();
    }
}
