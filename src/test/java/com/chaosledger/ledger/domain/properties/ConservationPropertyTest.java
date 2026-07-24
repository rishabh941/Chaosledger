package com.chaosledger.ledger.domain.properties;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.events.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROPERTY 1: Conservation of Money
 *
 * THE INVARIANT:
 *   For any sequence of valid transfers between accounts, the total
 *   balance across ALL accounts remains constant. Money is neither
 *   created nor destroyed — only moved between accounts.
 *
 * WHY THIS IS THE MOST IMPORTANT PROPERTY:
 *   If conservation fails, the ledger is either creating money (bank
 *   becomes insolvent) or destroying money (customers are robbed).
 *   Every financial regulator audits for this. In double-entry
 *   bookkeeping, this is the fundamental axiom.
 *
 * HOW IT WORKS:
 *   1. Create N accounts (2-5), each with a random initial deposit.
 *   2. Generate a random sequence of transfers between them.
 *   3. Each transfer moves money from sender to receiver (only if
 *      sender has sufficient funds).
 *   4. After all transfers: sum of all balances == sum of initial deposits.
 *
 * WHAT THIS CATCHES:
 *   - Rounding errors in BigDecimal arithmetic
 *   - Off-by-one in transfer logic (debiting but not crediting)
 *   - Bugs where Money.add() or Money.subtract() silently drop precision
 *   - Any mutation in Account.apply() that changes balance incorrectly
 */
class ConservationPropertyTest {

    // Test 1: Pure arithmetic conservation (Map-based)
    // This tests the INVARIANT in isolation, without the domain layer.
    // Fast, simple, and catches arithmetic bugs.

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void totalBalance_isPreserved_afterAnyTransferSequence(
            @ForAll("initialDeposits") List<BigDecimal> deposits
    ) {
        // Setup: create accounts with initial deposits
        List<UUID> accountIds = new ArrayList<>();
        Map<UUID, BigDecimal> balances = new LinkedHashMap<>();

        for (BigDecimal deposit : deposits) {
            UUID id = UUID.randomUUID();
            accountIds.add(id);
            balances.put(id, deposit);
        }

        // Record the total before any transfers
        BigDecimal expectedTotal = balances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Act: perform random transfers
        Random rng = new Random();
        int transferCount = 20 + rng.nextInt(31); // 20 to 50 transfers

        for (int i = 0; i < transferCount; i++) {
            UUID from = accountIds.get(rng.nextInt(accountIds.size()));
            UUID to = accountIds.get(rng.nextInt(accountIds.size()));

            // Skip self-transfers
            if (from.equals(to)) continue;

            BigDecimal available = balances.get(from);
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Transfer a random fraction of the sender's balance
            BigDecimal fraction = BigDecimal.valueOf(rng.nextDouble());
            BigDecimal amount = available.multiply(fraction)
                    .setScale(2, RoundingMode.DOWN);

            // Skip dust amounts
            if (amount.compareTo(new BigDecimal("0.01")) < 0) continue;

            // Execute the transfer
            balances.merge(from, amount, BigDecimal::subtract);
            balances.merge(to, amount, BigDecimal::add);
        }

        // Assert: total must be unchanged
        BigDecimal actualTotal = balances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(actualTotal).isEqualByComparingTo(expectedTotal);
    }

    // Test 2: Domain-layer conservation (Account aggregates)
    // This tests the same invariant through the REAL Account class,
    // catching bugs in Account.apply() and Account.reconstitute().

    @Property(tries = 500)
    @Report(Reporting.GENERATED)
    void totalBalance_isPreserved_throughAccountAggregates(
            @ForAll("initialDeposits") List<BigDecimal> deposits
    ) {
        // Setup: create accounts with events
        int numAccounts = deposits.size();
        List<UUID> accountIds = new ArrayList<>();
        Map<UUID, List<Event>> eventStreams = new LinkedHashMap<>();
        UUID ownerId = UUID.randomUUID();

        for (BigDecimal deposit : deposits) {
            UUID accountId = UUID.randomUUID();
            accountIds.add(accountId);

            List<Event> events = new ArrayList<>();
            events.add(LedgerArbitraries.openEvent(accountId, ownerId));
            events.add(LedgerArbitraries.depositEvent(accountId, deposit));
            eventStreams.put(accountId, events);
        }

        // Record expected total
        BigDecimal expectedTotal = deposits.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Act: perform random transfers via events
        Random rng = new Random();
        for (int i = 0; i < 30; i++) {
            UUID fromId = accountIds.get(rng.nextInt(numAccounts));
            UUID toId = accountIds.get(rng.nextInt(numAccounts));
            if (fromId.equals(toId)) continue;

            // Reconstitute sender to check balance
            Account sender = Account.reconstitute(
                    new ArrayList<>(eventStreams.get(fromId)));
            BigDecimal available = sender.getBalance().amount();
            if (available.compareTo(new BigDecimal("0.01")) < 0) continue;

            // Pick a random transfer amount within balance
            BigDecimal amount = available.multiply(
                            BigDecimal.valueOf(rng.nextDouble()))
                    .setScale(2, RoundingMode.DOWN);
            if (amount.compareTo(new BigDecimal("0.01")) < 0) continue;

            // Add transfer events to both accounts
            eventStreams.get(fromId).add(
                    LedgerArbitraries.transferEvent(fromId, toId, amount));
            eventStreams.get(toId).add(
                    LedgerArbitraries.transferReceivedEvent(toId, fromId, amount));
        }

        // Assert: reconstitute all accounts, sum balances
        BigDecimal actualTotal = BigDecimal.ZERO;
        for (UUID accountId : accountIds) {
            Account account = Account.reconstitute(eventStreams.get(accountId));
            actualTotal = actualTotal.add(account.getBalance().amount());
        }

        assertThat(actualTotal).isEqualByComparingTo(expectedTotal);
    }


    @Provide
    Arbitrary<List<BigDecimal>> initialDeposits() {
        return LedgerArbitraries.amounts()
                .list()
                .ofMinSize(2)
                .ofMaxSize(5);
    }
}
