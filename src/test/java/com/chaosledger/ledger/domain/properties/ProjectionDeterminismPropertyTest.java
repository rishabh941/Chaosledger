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
 * PROPERTY 4: Projection Determinism
 *
 * THE INVARIANT:
 *   Replaying the same sequence of events ALWAYS yields the same
 *   account state. Account.reconstitute(events) is a pure function —
 *   no randomness, no hidden state, no timestamps affecting balances.
 *
 * WHY DETERMINISM IS NON-NEGOTIABLE:
 *   ChaosLedger is built on event sourcing. The source of truth is
 *   the event log, not the current balances. If replaying events gives
 *   different results on different runs, then:
 *   - You can't reconstruct account history for auditing
 *   - You can't replicate state across nodes (Phase 3)
 *   - You can't rebuild projections after a crash
 *   - The entire event sourcing architecture is broken
 *
 *   This property proves your replay is deterministic.
 *
 * HOW IT WORKS:
 *   1. Generate a random sequence of events (AccountOpened + N deposits).
 *   2. Replay them through Account.reconstitute() TWICE.
 *   3. Compare every field of the resulting Account objects.
 *   4. They must be identical.
 *
 * WHAT THIS CATCHES:
 *   - Use of Instant.now() or Random inside apply() (non-determinism)
 *   - Mutable state leaking between reconstitution calls
 *   - HashMap iteration order affecting calculations
 *   - Floating-point accumulation errors (BigDecimal prevents this,
 *     but the test proves it)
 *
 * GENERATOR NOTE:
 *   The generator only uses deposits, not withdrawals. Why?
 *   Account.reconstitute() doesn't validate — it just applies.
 *   A withdrawal of 1000 on a balance of 0 would produce -1000,
 *   which is "correct" replay behavior (validation happened before
 *   the event was stored). But mixing random withdrawals could hit
 *   the Money record constructor's scale check. Keep the generator
 *   simple; test the invariant cleanly.
 */
class ProjectionDeterminismPropertyTest {

    // Main property: replay consistency

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void replayingSameEvents_alwaysProducesSameState(
            @ForAll("eventSequences") List<Event> events
    ) {
        // Replay the EXACT same events twice
        Account first = Account.reconstitute(new ArrayList<>(events));
        Account second = Account.reconstitute(new ArrayList<>(events));

        // Every field must be identical
        assertThat(first.getBalance().amount())
                .as("Balance must be deterministic")
                .isEqualByComparingTo(second.getBalance().amount());

        assertThat(first.getVersion())
                .as("Version must be deterministic")
                .isEqualTo(second.getVersion());

        assertThat(first.getCurrency())
                .as("Currency must be deterministic")
                .isEqualTo(second.getCurrency());

        assertThat(first.getStatus())
                .as("Status must be deterministic")
                .isEqualTo(second.getStatus());

        assertThat(first.getAccountId())
                .as("AccountId must be deterministic")
                .isEqualTo(second.getAccountId());

        assertThat(first.getOwnerId())
                .as("OwnerId must be deterministic")
                .isEqualTo(second.getOwnerId());
    }

    // Extended: replay 10 times
    // Extra paranoia — replay the same events 10 times and verify
    // all 10 results are identical. Catches subtle non-determinism
    // that might not show up in just 2 replays (e.g., GC-dependent
    // behavior, lazy initialization races).

    @Property(tries = 200)
    void replayingTenTimes_allProduceSameBalance(
            @ForAll("eventSequences") List<Event> events
    ) {
        BigDecimal expectedBalance = Account.reconstitute(
                new ArrayList<>(events)).getBalance().amount();

        for (int i = 0; i < 10; i++) {
            Account replayed = Account.reconstitute(new ArrayList<>(events));
            assertThat(replayed.getBalance().amount())
                    .as("Replay #%d must match", i)
                    .isEqualByComparingTo(expectedBalance);
        }
    }

    // Extended: complex event sequences
    // Mix deposits, withdrawals, transfers, and transfer-received events.
    // This tests determinism across ALL event types, not just deposits.

    @Property(tries = 500)
    void complexEventSequence_isDeterministic(
            @ForAll("complexSequences") List<Event> events
    ) {
        Account first = Account.reconstitute(new ArrayList<>(events));
        Account second = Account.reconstitute(new ArrayList<>(events));

        assertThat(first.getBalance().amount())
                .isEqualByComparingTo(second.getBalance().amount());
        assertThat(first.getVersion())
                .isEqualTo(second.getVersion());
    }


    @Provide
    Arbitrary<List<Event>> eventSequences() {
        // Fixed accountId/ownerId so events form a coherent sequence
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // Always starts with AccountOpened
        AccountOpened opened = LedgerArbitraries.openEvent(accountId, ownerId);

        // Then random deposits (safe — can't cause validation issues)
        Arbitrary<Event> deposits = LedgerArbitraries.amounts()
                .map(amt -> (Event) LedgerArbitraries.depositEvent(accountId, amt));

        return deposits.list()
                .ofMinSize(0)
                .ofMaxSize(20)
                .map(depositList -> {
                    List<Event> all = new ArrayList<>();
                    all.add(opened);
                    all.addAll(depositList);
                    return all;
                });
    }

    @Provide
    Arbitrary<List<Event>> complexSequences() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();

        AccountOpened opened = LedgerArbitraries.openEvent(accountId, ownerId);

        // Large initial deposit to prevent negative balances during replay
        MoneyDeposited bigDeposit = LedgerArbitraries.depositEvent(
                accountId, new BigDecimal("99999.00"));

        // Mix of event types
        Arbitrary<Event> deposits = LedgerArbitraries.smallAmounts()
                .map(amt -> (Event) LedgerArbitraries.depositEvent(accountId, amt));
        Arbitrary<Event> withdrawals = LedgerArbitraries.smallAmounts()
                .map(amt -> (Event) LedgerArbitraries.withdrawEvent(accountId, amt));
        Arbitrary<Event> transfersOut = LedgerArbitraries.smallAmounts()
                .map(amt -> (Event) LedgerArbitraries.transferEvent(
                        accountId, otherAccountId, amt));
        Arbitrary<Event> transfersIn = LedgerArbitraries.smallAmounts()
                .map(amt -> (Event) LedgerArbitraries.transferReceivedEvent(
                        accountId, otherAccountId, amt));

        return Arbitraries.oneOf(deposits, withdrawals, transfersOut, transfersIn)
                .list()
                .ofMinSize(1)
                .ofMaxSize(15)
                .map(eventList -> {
                    List<Event> all = new ArrayList<>();
                    all.add(opened);
                    all.add(bigDeposit); // ensure positive balance
                    all.addAll(eventList);
                    return all;
                });
    }
}
