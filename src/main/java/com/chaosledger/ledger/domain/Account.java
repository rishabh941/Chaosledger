package com.chaosledger.ledger.domain;

import com.chaosledger.ledger.domain.events.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class Account {

    private UUID accountId;
    private UUID ownerId;
    private Money balance;
    private String currency;
    private AccountStatus status;
    private long version;

    private Account() {}

    public static Account reconstitute(List<Event> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Cannot reconstitute account from empty event list");
        }
        Account account = new Account();
        for (Event event : events) {
            account.apply(event);
            account.version++;
        }
        return account;
    }

    public static Account open(UUID accountId, UUID ownerId, String currency) {
        Account account = new Account();
        account.accountId = accountId;
        account.ownerId = ownerId;
        account.currency = currency;
        account.balance = Money.zero(currency);
        account.status = AccountStatus.OPEN;
        account.version = 0;
        return account;
    }

    private void apply(Event event) {
        switch (event) {
            case AccountOpened e -> {
                this.accountId = e.aggregateId();
                this.ownerId = e.ownerId();
                this.currency = e.currency();
                this.balance = Money.zero(e.currency());
                this.status = AccountStatus.OPEN;
            }
            case MoneyDeposited e -> {
                this.balance = this.balance.add(Money.of(e.amount(), e.currency()));
            }
            case MoneyWithdrawn e -> {
                this.balance = this.balance.subtract(Money.of(e.amount(), e.currency()));
            }
            case MoneyTransferred e -> {
                this.balance = this.balance.subtract(Money.of(e.amount(), e.currency()));
            }
            case TransferReceived e -> {
                this.balance = this.balance.add(Money.of(e.amount(), e.currency()));
            }
            default -> throw new IllegalStateException("Unknown event type: " + event.getClass().getSimpleName());
        }
    }

    public void validateDeposit(BigDecimal amount) {
        assertOpen();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
    }

    public void validateWithdrawal(BigDecimal amount) {
        assertOpen();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive");
        }
        Money withdrawal = Money.of(amount, currency);
        if (!balance.isGreaterThanOrEqual(withdrawal)) {
            throw new InsufficientBalanceException(accountId, balance.amount(), amount);
        }
    }

    public void validateTransferOut(BigDecimal amount) {
        assertOpen();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        Money transferAmount = Money.of(amount, currency);
        if (!balance.isGreaterThanOrEqual(transferAmount)) {
            throw new InsufficientBalanceException(accountId, balance.amount(), amount);
        }
    }

    private void assertOpen() {
        if (status != AccountStatus.OPEN) {
            throw new AccountClosedException(accountId);
        }
    }

    public UUID getAccountId() { return accountId; }
    public UUID getOwnerId() { return ownerId; }
    public Money getBalance() { return balance; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public long getVersion() { return version; }
}
