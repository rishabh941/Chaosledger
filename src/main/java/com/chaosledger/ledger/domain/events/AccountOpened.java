package com.chaosledger.ledger.domain.events;

import java.time.Instant;
import java.util.UUID;


public record AccountOpened(
        UUID eventId,
        UUID aggregateId,
        UUID ownerId,
        String currency,
        Instant occurredAt
) implements Event {

    @Override
    public String aggregateType() {
        return "Account";
    }
}