package com.chaosledger.ledger.domain.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferReceived(
        UUID eventId,
        UUID aggregateId,
        UUID fromAccountId,
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
