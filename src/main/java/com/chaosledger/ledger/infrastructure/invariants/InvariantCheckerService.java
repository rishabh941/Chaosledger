package com.chaosledger.ledger.infrastructure.invariants;

import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
import com.chaosledger.ledger.domain.invariants.*;
import com.chaosledger.ledger.infrastructure.eventstore.EventEntity;
import com.chaosledger.ledger.infrastructure.eventstore.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Orchestrates all invariant checks against the live event store.
 *
 * This service:
 * 1. Loads ALL events from the database (via EventRepository)
 * 2. Groups them by aggregate ID
 * 3. Deserializes each into domain Event objects
 * 4. Passes the grouped map to each Invariant implementation
 * 5. Stores results in a thread-safe map
 *
 * The InvariantScheduler calls runAllChecks() every 10 seconds.
 * The InvariantController reads getLatestResults() on each HTTP request.
 *
 * Thread safety:
 *   - latestResults is a ConcurrentHashMap (safe for concurrent reads + writes)
 *   - lastRunAt is an AtomicReference (safe for concurrent reads + writes)
 *   - The scheduler and HTTP threads never interfere with each other
 */
@Service
@Slf4j
public class InvariantCheckerService {

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final List<Invariant> invariants;

    // Thread-safe storage for latest results
    private final ConcurrentHashMap<String, InvariantResult> latestResults = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();
    private final AtomicReference<Long> lastEventCount = new AtomicReference<>(0L);

    public InvariantCheckerService(EventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;

        // Register all invariants
        this.invariants = List.of(
                new EventOrderingInvariant(),
                new AccountIntegrityInvariant(),
                new NoNegativeBalanceInvariant(),
                new ProjectionDeterminismInvariant(),
                new ConservationInvariant()
        );

        log.info("InvariantCheckerService initialized with {} invariants: {}",
                invariants.size(),
                invariants.stream().map(Invariant::name).collect(Collectors.joining(", ")));
    }

    /**
     * Run all invariant checks against the current state of the event store.
     * Called by InvariantScheduler every 10 seconds.
     */
    public void runAllChecks() {
        Instant runStart = Instant.now();
        log.debug("Starting invariant check cycle");

        try {
            // Step 1: Load ALL events from the database
            List<EventEntity> allEntities = eventRepository.findAll();
            lastEventCount.set((long) allEntities.size());

            if (allEntities.isEmpty()) {
                log.debug("No events in store — skipping invariant checks");
                // Still mark all as passed (vacuously true — no data to violate)
                for (Invariant invariant : invariants) {
                    latestResults.put(invariant.name(),
                            InvariantResult.passed(invariant.name(), Duration.ZERO));
                }
                lastRunAt.set(Instant.now());
                return;
            }

            // Step 2: Deserialize and group by aggregate ID
            Map<UUID, List<Event>> eventsByAggregate = groupAndDeserialize(allEntities);

            // Step 3: Run each invariant
            for (Invariant invariant : invariants) {
                try {
                    InvariantResult result = invariant.check(eventsByAggregate);
                    latestResults.put(invariant.name(), result);

                    if (result.status() == InvariantStatus.FAILED) {
                        log.warn("INVARIANT VIOLATED: {} — {}", invariant.name(), result.message());
                    } else if (result.status() == InvariantStatus.ERROR) {
                        log.error("INVARIANT ERROR: {} — {}", invariant.name(), result.message());
                    } else {
                        log.debug("Invariant passed: {} ({}ms)",
                                invariant.name(), result.duration().toMillis());
                    }
                } catch (Exception e) {
                    log.error("Unexpected error running invariant: {}", invariant.name(), e);
                    latestResults.put(invariant.name(),
                            InvariantResult.error(invariant.name(),
                                    "Unexpected: " + e.getMessage(),
                                    Duration.between(runStart, Instant.now())));
                }
            }

            lastRunAt.set(Instant.now());
            Duration totalDuration = Duration.between(runStart, Instant.now());
            log.info("Invariant check cycle complete: {} checks in {}ms, {} events scanned",
                    invariants.size(), totalDuration.toMillis(), allEntities.size());

        } catch (Exception e) {
            log.error("Failed to run invariant check cycle", e);
        }
    }

    /**
     * Get the latest results from all invariant checks.
     * Called by InvariantController on each GET /api/invariants request.
     */
    public List<InvariantResult> getLatestResults() {
        return new ArrayList<>(latestResults.values());
    }

    /**
     * Get metadata about the checker service itself.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("invariantCount", invariants.size());
        status.put("lastRunAt", lastRunAt.get());
        status.put("eventsScanned", lastEventCount.get());

        long passed = latestResults.values().stream()
                .filter(r -> r.status() == InvariantStatus.PASSED).count();
        long failed = latestResults.values().stream()
                .filter(r -> r.status() == InvariantStatus.FAILED).count();
        long errors = latestResults.values().stream()
                .filter(r -> r.status() == InvariantStatus.ERROR).count();

        status.put("passed", passed);
        status.put("failed", failed);
        status.put("errors", errors);
        status.put("allPassing", failed == 0 && errors == 0 && passed > 0);

        return status;
    }

    // Private Helpers

    private Map<UUID, List<Event>> groupAndDeserialize(List<EventEntity> entities) {
        Map<UUID, List<Event>> result = new LinkedHashMap<>();

        // Sort by aggregate ID then by version to ensure correct order
        entities.sort(Comparator
                .comparing(EventEntity::getAggregateId)
                .thenComparing(EventEntity::getVersion));

        for (EventEntity entity : entities) {
            Event event = deserialize(entity);
            result.computeIfAbsent(entity.getAggregateId(), k -> new ArrayList<>())
                    .add(event);
        }

        return result;
    }

    private Event deserialize(EventEntity entity) {
        try {
            Class<?> eventClass = Class.forName(
                    "com.chaosledger.ledger.domain.events." + entity.getEventType());
            return (Event) objectMapper.treeToValue(entity.getPayload(), eventClass);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize event " + entity.getId()
                            + " of type " + entity.getEventType(), e);
        }
    }
}
