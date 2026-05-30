package com.chaosledger.ledger.domain.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MoneyWithdrawn(
        UUID eventId,
        UUID aggregateId,
        BigDecimal amount,
        String currency,
        UUID idempotencyKey,
        Instant occurredAt
) implements Event {

    @Override
    public String aggregateType() {
        return "Account";
    }
}
