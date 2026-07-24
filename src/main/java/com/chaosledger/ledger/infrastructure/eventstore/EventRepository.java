package com.chaosledger.ledger.infrastructure.eventstore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, UUID> {

    /**
     * Load all events for a given aggregate, ordered by version ascending.
     * This is the canonical "rebuild state from event log" query.
     */
    List<EventEntity> findByAggregateIdOrderByVersionAsc(UUID aggregateId);

    /**
     * Find the highest version number for an aggregate.
     * Used during append to know what version to assign next.
     * Returns null if no events exist for this aggregate yet.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT MAX(e.version) FROM EventEntity e WHERE e.aggregateId = :aggregateId"
    )
    Long findMaxVersionForAggregate(UUID aggregateId);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT COUNT(DISTINCT aggregate_id) FROM events WHERE event_type = 'AccountOpened'",
            nativeQuery = true)
    long countAccounts();

    @org.springframework.data.jpa.repository.Query(
            value = """
                SELECT COALESCE(SUM(CASE \
                  WHEN event_type IN ('MoneyDeposited', 'TransferReceived') THEN CAST(payload->>'amount' AS NUMERIC) \
                  WHEN event_type IN ('MoneyWithdrawn', 'MoneyTransferred') THEN -CAST(payload->>'amount' AS NUMERIC) \
                  ELSE 0 \
                END), 0) FROM events""",
            nativeQuery = true)
    java.math.BigDecimal computeTotalBalance();
}