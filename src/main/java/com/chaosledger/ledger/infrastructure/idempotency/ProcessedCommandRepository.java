package com.chaosledger.ledger.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedCommandRepository extends JpaRepository<ProcessedCommandEntity, UUID> {
}
