package com.chaosledger.ledger.domain;

import java.util.UUID;

public interface IdempotencyStore {
    boolean exists(UUID idempotencyKey);
    void record(UUID idempotencyKey, String commandType, UUID aggregateId);
    void recordIfAbsent(UUID idempotencyKey, String commandType, UUID aggregateId);
}
