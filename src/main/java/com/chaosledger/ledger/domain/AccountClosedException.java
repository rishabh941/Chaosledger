package com.chaosledger.ledger.domain;

import java.util.UUID;

public class AccountClosedException extends RuntimeException {

    private final UUID accountId;

    public AccountClosedException(UUID accountId) {
        super("Account " + accountId + " is closed");
        this.accountId = accountId;
    }

    public UUID getAccountId() { return accountId; }
}
