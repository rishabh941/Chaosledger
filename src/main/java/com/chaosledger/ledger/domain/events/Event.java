package com.chaosledger.ledger.domain.events;

import java.time.Instant;
import java.util.UUID;
public interface Event {
    UUID eventId();
    UUID aggregateId();
    String aggregateType();
    Instant occurredAt();

}
