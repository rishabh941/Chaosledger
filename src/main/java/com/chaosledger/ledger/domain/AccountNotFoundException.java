package com.chaosledger.ledger.domain;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    private final UUID accountId;

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public UUID getAccountId() { return accountId; }
}
