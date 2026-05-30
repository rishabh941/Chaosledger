package com.chaosledger.ledger.api.dto;

import com.chaosledger.ledger.domain.Account;
import com.chaosledger.ledger.domain.AccountStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        UUID ownerId,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        long version
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountId(),
                account.getOwnerId(),
                account.getBalance().amount(),
                account.getCurrency(),
                account.getStatus(),
                account.getVersion()
        );
    }
}
