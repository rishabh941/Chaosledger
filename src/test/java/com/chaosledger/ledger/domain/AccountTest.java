package com.chaosledger.ledger.domain;

import com.chaosledger.ledger.domain.events.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the Account aggregate.
 *
 * These tests DO NOT need a database. They verify the event replay logic
 * and validation rules in isolation. Fast to run (~milliseconds each).
 *
 * Week 4 learning point: Testing the aggregate without infrastructure
 * is a key benefit of hexagonal architecture. If Account.reconstitute()
 * has a bug, these tests catch it instantly without waiting for Postgres.
 */
class AccountTest {

    // =====================================================================
    // Helper factories — build events without boilerplate
    // =====================================================================

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final String CURRENCY = "INR";

    private AccountOpened opened() {
        return new AccountOpened(
                UUID.randomUUID(), ACCOUNT_ID, OWNER_ID, CURRENCY, Instant.now());
    }

    private MoneyDeposited deposited(String amount) {
        return new MoneyDeposited(
                UUID.randomUUID(), ACCOUNT_ID,
                new BigDecimal(amount), CURRENCY,
                UUID.randomUUID(), Instant.now());
    }

    private MoneyWithdrawn withdrawn(String amount) {
        return new MoneyWithdrawn(
                UUID.randomUUID(), ACCOUNT_ID,
                new BigDecimal(amount), CURRENCY,
                UUID.randomUUID(), Instant.now());
    }

    private MoneyTransferred transferred(String amount) {
        return new MoneyTransferred(
                UUID.randomUUID(), ACCOUNT_ID, UUID.randomUUID(),
                new BigDecimal(amount), CURRENCY,
                UUID.randomUUID(), Instant.now());
    }

    private TransferReceived received(String amount) {
        return new TransferReceived(
                UUID.randomUUID(), ACCOUNT_ID, UUID.randomUUID(),
                new BigDecimal(amount), CURRENCY,
                UUID.randomUUID(), Instant.now());
    }

    // =====================================================================
    // Reconstitution tests
    // =====================================================================

    @Nested
    @DisplayName("Account.reconstitute()")
    class ReconstitutionTests {

        @Test
        @DisplayName("Single AccountOpened → balance 0, status OPEN, version 1")
        void reconstitute_fromOpened_hasZeroBalance() {
            Account account = Account.reconstitute(List.of(opened()));

            assertThat(account.getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(account.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(account.getBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getCurrency()).isEqualTo(CURRENCY);
            assertThat(account.getStatus()).isEqualTo(AccountStatus.OPEN);
            assertThat(account.getVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Opened + Deposit(1000) → balance 1000")
        void reconstitute_withDeposit_correctBalance() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("1000.00")));

            assertThat(account.getBalance().amount())
                    .isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(account.getVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("Opened + Deposit(1000) + Withdraw(400) → balance 600")
        void reconstitute_depositThenWithdraw_correctBalance() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("1000.00"), withdrawn("400.00")));

            assertThat(account.getBalance().amount())
                    .isEqualByComparingTo(new BigDecimal("600.00"));
            assertThat(account.getVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Transfer out reduces balance")
        void reconstitute_transferOut_reducesBalance() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("1000.00"), transferred("300.00")));

            assertThat(account.getBalance().amount())
                    .isEqualByComparingTo(new BigDecimal("700.00"));
        }

        @Test
        @DisplayName("Transfer received increases balance")
        void reconstitute_transferReceived_increasesBalance() {
            Account account = Account.reconstitute(List.of(
                    opened(), received("500.00")));

            assertThat(account.getBalance().amount())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("Empty event list throws IllegalArgumentException")
        void reconstitute_emptyList_throws() {
            assertThatThrownBy(() -> Account.reconstitute(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Complex event sequence — balance computed correctly")
        void reconstitute_complexSequence_correctBalance() {
            // Open → Deposit 1000 → Withdraw 200 → Transfer out 300 → Receive 150
            // Expected: 0 + 1000 - 200 - 300 + 150 = 650
            Account account = Account.reconstitute(List.of(
                    opened(),
                    deposited("1000.00"),
                    withdrawn("200.00"),
                    transferred("300.00"),
                    received("150.00")));

            assertThat(account.getBalance().amount())
                    .isEqualByComparingTo(new BigDecimal("650.00"));
            assertThat(account.getVersion()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Replaying same events twice yields same result (determinism)")
        void reconstitute_isDeterministic() {
            List<Event> events = List.of(
                    opened(), deposited("500.00"), withdrawn("100.00"));

            Account first = Account.reconstitute(events);
            Account second = Account.reconstitute(events);

            assertThat(first.getBalance().amount())
                    .isEqualByComparingTo(second.getBalance().amount());
            assertThat(first.getVersion()).isEqualTo(second.getVersion());
        }
    }

    // =====================================================================
    // Validation tests
    // =====================================================================

    @Nested
    @DisplayName("Validation rules")
    class ValidationTests {

        @Test
        @DisplayName("Deposit positive amount → no exception")
        void validateDeposit_positiveAmount_succeeds() {
            Account account = Account.reconstitute(List.of(opened()));
            account.validateDeposit(new BigDecimal("100.00"));
            // No exception thrown → test passes
        }

        @Test
        @DisplayName("Deposit zero → IllegalArgumentException")
        void validateDeposit_zero_throws() {
            Account account = Account.reconstitute(List.of(opened()));
            assertThatThrownBy(() -> account.validateDeposit(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Deposit negative → IllegalArgumentException")
        void validateDeposit_negative_throws() {
            Account account = Account.reconstitute(List.of(opened()));
            assertThatThrownBy(() -> account.validateDeposit(new BigDecimal("-100.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Withdraw within balance → no exception")
        void validateWithdrawal_withinBalance_succeeds() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("1000.00")));
            account.validateWithdrawal(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("Withdraw exceeds balance → InsufficientBalanceException")
        void validateWithdrawal_exceedsBalance_throws() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("100.00")));
            assertThatThrownBy(() -> account.validateWithdrawal(new BigDecimal("999.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("Withdraw exact balance → succeeds (zero balance allowed)")
        void validateWithdrawal_exactBalance_succeeds() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("500.00")));
            account.validateWithdrawal(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("Transfer out within balance → no exception")
        void validateTransferOut_withinBalance_succeeds() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("1000.00")));
            account.validateTransferOut(new BigDecimal("300.00"));
        }

        @Test
        @DisplayName("Transfer out exceeds balance → InsufficientBalanceException")
        void validateTransferOut_exceedsBalance_throws() {
            Account account = Account.reconstitute(List.of(
                    opened(), deposited("100.00")));
            assertThatThrownBy(() -> account.validateTransferOut(new BigDecimal("999.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }
}
