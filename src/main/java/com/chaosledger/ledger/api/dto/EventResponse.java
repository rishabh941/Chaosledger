package com.chaosledger.ledger.api.dto;

import com.chaosledger.ledger.domain.events.Event;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        Instant occurredAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.eventId(),
                event.aggregateId(),
                event.getClass().getSimpleName(),
                event.occurredAt()
        );
    }
}
