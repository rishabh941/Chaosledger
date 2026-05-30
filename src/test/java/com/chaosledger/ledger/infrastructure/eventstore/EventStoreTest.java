package com.chaosledger.ledger.infrastructure.eventstore;

import com.chaosledger.ledger.IntegrationTestBase;
import com.chaosledger.ledger.domain.events.AccountOpened;
import com.chaosledger.ledger.domain.events.ConcurrencyException;
import com.chaosledger.ledger.domain.events.Event;
import com.chaosledger.ledger.domain.events.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class EventStoreTest extends IntegrationTestBase {

    @Autowired
    private EventStore eventStore;

    @Autowired
    private EventRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
        repository.flush();
    }

    @Test
    @DisplayName("Newly created aggregate has version 0")
    void newAggregate_hasVersionZero() {
        UUID accountId = UUID.randomUUID();
        assertThat(eventStore.currentVersion(accountId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("Append a single event increases version to 1")
    void singleAppend_versionBecomesOne() {
        UUID accountId = UUID.randomUUID();
        AccountOpened event = newAccountOpened(accountId);

        eventStore.append(accountId, 0L, List.of(event));

        assertThat(eventStore.currentVersion(accountId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Loading events returns them in version order")
    void loadEvents_returnsInVersionOrder() {
        UUID accountId = UUID.randomUUID();
        AccountOpened first = newAccountOpened(accountId);
        AccountOpened second = newAccountOpened(accountId);
        AccountOpened third = newAccountOpened(accountId);

        eventStore.append(accountId, 0L, List.of(first));
        eventStore.append(accountId, 1L, List.of(second));
        eventStore.append(accountId, 2L, List.of(third));

        List<Event> loaded = eventStore.loadEvents(accountId);

        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).eventId()).isEqualTo(first.eventId());
        assertThat(loaded.get(1).eventId()).isEqualTo(second.eventId());
        assertThat(loaded.get(2).eventId()).isEqualTo(third.eventId());
    }

    @Test
    @DisplayName("Appending with stale expectedVersion throws ConcurrencyException")
    void staleExpectedVersion_throwsConcurrencyException() {
        UUID accountId = UUID.randomUUID();
        eventStore.append(accountId, 0L, List.of(newAccountOpened(accountId)));

        // Now try to append again with expectedVersion=0 (stale - should be 1)
        assertThatThrownBy(() ->
                eventStore.append(accountId, 0L, List.of(newAccountOpened(accountId)))
        ).isInstanceOf(ConcurrencyException.class);
    }

    @Test
    @DisplayName("Events are isolated by aggregate ID")
    void eventsForDifferentAggregates_doNotInterfere() {
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        eventStore.append(accountA, 0L, List.of(newAccountOpened(accountA)));
        eventStore.append(accountB, 0L, List.of(newAccountOpened(accountB)));

        assertThat(eventStore.loadEvents(accountA)).hasSize(1);
        assertThat(eventStore.loadEvents(accountB)).hasSize(1);
    }

    @Test
    @DisplayName("Appending empty event list is rejected")
    void emptyEventList_isRejected() {
        UUID accountId = UUID.randomUUID();
        assertThatThrownBy(() ->
                eventStore.append(accountId, 0L, List.of())
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Loading events for nonexistent aggregate returns empty list")
    void nonexistentAggregate_returnsEmptyList() {
        UUID accountId = UUID.randomUUID();
        assertThat(eventStore.loadEvents(accountId)).isEmpty();
    }

    @Test
    @DisplayName("Concurrent appends to same aggregate: only one succeeds")
    void concurrentAppendsToSameAggregate_onlyOneSucceeds() throws Exception {
        UUID accountId = UUID.randomUUID();
        int numThreads = 50;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();  // All threads start at the same instant
                    eventStore.append(accountId, 0L, List.of(newAccountOpened(accountId)));
                    successCount.incrementAndGet();
                } catch (ConcurrencyException ex) {
                    conflictCount.incrementAndGet();
                } catch (Exception ex) {
                    // Other exceptions also count as failures
                    conflictCount.incrementAndGet();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();  // Release all threads
        finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
                .as("Exactly one thread should succeed in appending")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("All other threads should have detected a conflict")
                .isEqualTo(numThreads - 1);
        assertThat(eventStore.currentVersion(accountId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Multiple events appended in one call get sequential versions")
    void multipleEventsInOneAppend_getSequentialVersions() {
        UUID accountId = UUID.randomUUID();
        AccountOpened e1 = newAccountOpened(accountId);
        AccountOpened e2 = newAccountOpened(accountId);
        AccountOpened e3 = newAccountOpened(accountId);

        eventStore.append(accountId, 0L, List.of(e1, e2, e3));

        assertThat(eventStore.currentVersion(accountId)).isEqualTo(3L);
        assertThat(eventStore.loadEvents(accountId)).hasSize(3);
    }

    // === Helpers ===

    private AccountOpened newAccountOpened(UUID accountId) {
        return new AccountOpened(
                UUID.randomUUID(),  // unique event ID
                accountId,
                UUID.randomUUID(),  // owner ID
                "INR",
                Instant.now()
        );
    }
}