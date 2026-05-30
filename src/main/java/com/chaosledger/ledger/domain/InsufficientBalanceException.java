package com.chaosledger.ledger.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    private final UUID accountId;
    private final BigDecimal currentBalance;
    private final BigDecimal requestedAmount;

    public InsufficientBalanceException(UUID accountId, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super("Insufficient balance in account " + accountId
                + ": current=" + currentBalance + ", requested=" + requestedAmount);
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }

    public UUID getAccountId() { return accountId; }
    public BigDecimal getCurrentBalance() { return currentBalance; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
}
