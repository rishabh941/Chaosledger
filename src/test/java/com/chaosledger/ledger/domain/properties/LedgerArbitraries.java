package com.chaosledger.ledger.domain.properties;

import com.chaosledger.ledger.domain.events.*;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared jqwik generators (Arbitraries) for all property-based tests.
 *
 * Why centralize generators?
 * 1. Every property test needs random amounts, UUIDs, and events.
 *    Duplicating generator logic across 5 test classes means 5 places
 *    to fix when the domain model changes.
 * 2. Consistent constraints: amounts always have scale=2 (matching the
 *    Money record), always positive (matching validation rules), and
 *    capped at 100k (keeping shrunk failures readable).
 * 3. jqwik's shrinking works best when generators compose from standard
 *    Arbitraries. Custom Arbitraries built from Arbitraries.bigDecimals(),
 *    .integers(), etc. get shrinking for free.
 *
 * Usage in a property test:
 *   @Provide
 *   Arbitrary<BigDecimal> amounts() {
 *       return LedgerArbitraries.amounts();
 *   }
 */
public class LedgerArbitraries {

    private static final String DEFAULT_CURRENCY = "INR";
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000.00");

    // Amount Generators

    /**
     * Generates valid money amounts: 0.01 to 100,000.00 with scale=2.
     *
     * Why these bounds?
     * - Not 0: Zero deposits/withdrawals are rejected by Account.validateDeposit().
     * - Not negative: Negative amounts are rejected by validation.
     * - Cap at 100k: Prevents overflow noise in conservation tests and keeps
     *   shrunk cases human-readable.
     * - Scale=2: Matches BigDecimal(2) in the Money record constructor.
     *   If scale != 2, Money constructor throws ArithmeticException
     *   (RoundingMode.UNNECESSARY rejects any rounding).
     */
    public static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(MIN_AMOUNT, MAX_AMOUNT)
                .ofScale(2);
    }

    /**
     * Generates small amounts (0.01 to 1000.00).
     * Useful for tests where you want many operations without hitting
     * large-number edge cases.
     */
    public static Arbitrary<BigDecimal> smallAmounts() {
        return Arbitraries.bigDecimals()
                .between(MIN_AMOUNT, new BigDecimal("1000.00"))
                .ofScale(2);
    }

    // UUID Generators

    /**
     * Generates random UUIDs for accounts and owners.
     * Uses Arbitraries.create() so jqwik controls the randomness,
     * which enables proper shrinking.
     */
    public static Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    // Sequence Generators

    /**
     * Generates a random list of deposit amounts (1 to 20 deposits).
     * Used in conservation and symmetry tests where you need
     * a sequence of deposits to set up initial state.
     */
    public static Arbitrary<List<BigDecimal>> depositSequence() {
        return amounts().list().ofMinSize(1).ofMaxSize(20);
    }

    /**
     * Generates a random list of small deposit amounts (1 to 10).
     * Smaller sequences for faster tests.
     */
    public static Arbitrary<List<BigDecimal>> smallDepositSequence() {
        return smallAmounts().list().ofMinSize(1).ofMaxSize(10);
    }

    // Event Factories
    // These create domain Event objects directly, bypassing the
    // CommandHandler and REST layers. Property tests operate at the
    // domain level for speed (milliseconds per run, not seconds).

    /**
     * Creates an AccountOpened event for a given account and owner.
     * Every generated event sequence must start with this —
     * Account.reconstitute() throws on empty lists.
     */
    public static AccountOpened openEvent(UUID accountId, UUID ownerId) {
        return new AccountOpened(
                UUID.randomUUID(),   // eventId
                accountId,           // aggregateId
                ownerId,             // ownerId
                DEFAULT_CURRENCY,    // currency
                Instant.now()        // occurredAt
        );
    }

    /**
     * Creates a MoneyDeposited event.
     * The idempotencyKey is randomized — each event is unique.
     */
    public static MoneyDeposited depositEvent(UUID accountId, BigDecimal amount) {
        return new MoneyDeposited(
                UUID.randomUUID(),   // eventId
                accountId,           // aggregateId
                amount,              // amount
                DEFAULT_CURRENCY,    // currency
                UUID.randomUUID(),   // idempotencyKey
                Instant.now()        // occurredAt
        );
    }

    /**
     * Creates a MoneyWithdrawn event.
     */
    public static MoneyWithdrawn withdrawEvent(UUID accountId, BigDecimal amount) {
        return new MoneyWithdrawn(
                UUID.randomUUID(),   // eventId
                accountId,           // aggregateId
                amount,              // amount
                DEFAULT_CURRENCY,    // currency
                UUID.randomUUID(),   // idempotencyKey
                Instant.now()        // occurredAt
        );
    }

    /**
     * Creates a MoneyTransferred event (debit side of a transfer).
     */
    public static MoneyTransferred transferEvent(
            UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        return new MoneyTransferred(
                UUID.randomUUID(),   // eventId
                fromAccountId,       // aggregateId (sender)
                toAccountId,         // toAccountId (receiver)
                amount,              // amount
                DEFAULT_CURRENCY,    // currency
                UUID.randomUUID(),   // idempotencyKey
                Instant.now()        // occurredAt
        );
    }

    /**
     * Creates a TransferReceived event (credit side of a transfer).
     */
    public static TransferReceived transferReceivedEvent(
            UUID toAccountId, UUID fromAccountId, BigDecimal amount) {
        return new TransferReceived(
                UUID.randomUUID(),   // eventId
                toAccountId,         // aggregateId (receiver)
                fromAccountId,       // fromAccountId (sender)
                amount,              // amount
                DEFAULT_CURRENCY,    // currency
                UUID.randomUUID(),   // idempotencyKey
                Instant.now()        // occurredAt
        );
    }
}
