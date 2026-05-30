package com.chaosledger.ledger.infrastructure.idempotency;

import com.chaosledger.ledger.domain.IdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PostgresIdempotencyStore implements IdempotencyStore {

    private final ProcessedCommandRepository repository;

    @Override
    public boolean exists(UUID idempotencyKey) {
        return repository.existsById(idempotencyKey);
    }

    @Override
    public void record(UUID idempotencyKey, String commandType, UUID aggregateId) {
        repository.save(ProcessedCommandEntity.builder()
                .idempotencyKey(idempotencyKey)
                .commandType(commandType)
                .aggregateId(aggregateId)
                .processedAt(Instant.now())
                .build());
    }
}
