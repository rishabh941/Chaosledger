package com.chaosledger.ledger.domain.events;

import java.time.Instant;
import java.util.UUID;

// NO @JsonTypeInfo here — keep it clean
public interface Event {
    UUID eventId();
    UUID aggregateId();
    String aggregateType();
    Instant occurredAt();
}