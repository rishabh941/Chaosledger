package com.chaosledger.ledger.domain;

import java.util.UUID;

public class DuplicateCommandException extends RuntimeException {

    private final UUID idempotencyKey;

    public DuplicateCommandException(UUID idempotencyKey) {
        super("Command already processed with idempotency key: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getIdempotencyKey() { return idempotencyKey; }
}
