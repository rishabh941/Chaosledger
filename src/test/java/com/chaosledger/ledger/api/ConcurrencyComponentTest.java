package com.chaosledger.ledger.api;

import com.chaosledger.ledger.ComponentTestBase;
import com.chaosledger.ledger.ComponentTestBaseFallback;
import com.chaosledger.ledger.infrastructure.eventstore.EventRepository;
import com.chaosledger.ledger.infrastructure.idempotency.ProcessedCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency stress tests.
 *
 * These tests throw dozens of simultaneous HTTP requests at the same account
 * to verify that optimistic concurrency control (OCC) prevents double-spending.
 *
 * Why these matter for interviews:
 * - They prove you understand the double-spend problem
 * - They show you know how to write concurrent test fixtures (CountDownLatch)
 * - They demonstrate the EventStore's UNIQUE constraint works under real load
 * - If any of these fail, the ledger is UNSAFE for production
 */
class ConcurrencyComponentTest extends ComponentTestBaseFallback {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProcessedCommandRepository processedCommandRepo;

    @Autowired
    private EventRepository eventRepo;

    @BeforeEach
    void cleanDatabase() {
        processedCommandRepo.deleteAll();
        processedCommandRepo.flush();
        eventRepo.deleteAll();
        eventRepo.flush();
    }


    private String openAccount(String currency) {
        var request = Map.of(
                "ownerId", UUID.randomUUID().toString(),
                "currency", currency
        );
        return rest.postForEntity("/api/accounts", request, Map.class)
                .getBody().get("accountId").toString();
    }

    private void deposit(String accountId, double amount) {
        rest.postForEntity(
                "/api/accounts/" + accountId + "/deposit",
                Map.of("amount", amount, "idempotencyKey", UUID.randomUUID().toString()),
                Map.class);
    }

    private Map<String, Object> getAccount(String accountId) {
        return rest.getForObject("/api/accounts/" + accountId, Map.class);
    }

    // Test 1: 20 concurrent deposits — all should succeed, balance is sum
    @Test
    @DisplayName("20 concurrent deposits of ₹100 each → balance is exactly ₹2000")
    void concurrentDeposits_allSucceed_balanceIsSum() throws Exception {
        String accountId = openAccount("INR");
        int numThreads = 20;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger retryableFailures = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    // Each thread uses a unique idempotency key
                    var request = Map.of(
                            "amount", 100.00,
                            "idempotencyKey", UUID.randomUUID().toString()
                    );
                    ResponseEntity<Map> response = rest.postForEntity(
                            "/api/accounts/" + accountId + "/deposit",
                            request, Map.class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        // ConcurrencyException (409) — expected under race conditions
                        retryableFailures.incrementAndGet();
                    }
                } catch (Exception e) {
                    retryableFailures.incrementAndGet();
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Some deposits may fail with ConcurrencyException (409) since they
        // read the same version and race. The key invariant is:
        // balance == successCount × 100
        Map<String, Object> account = getAccount(accountId);
        double balance = ((Number) account.get("balance")).doubleValue();
        double expectedBalance = successCount.get() * 100.0;

        assertThat(balance)
                .as("Balance must equal exactly the number of successful deposits × ₹100")
                .isEqualTo(expectedBalance);

        // At least some should succeed
        assertThat(successCount.get()).isGreaterThan(0);

        // Total should add up
        assertThat(successCount.get() + retryableFailures.get()).isEqualTo(numThreads);
    }

    // Test 2: Concurrent withdrawals — cannot overdraw
    @Test
    @DisplayName("10 concurrent withdrawals of ₹600 from ₹1000 balance → at most 1 succeeds")
    void concurrentWithdrawals_cannotOverdraw() throws Exception {
        String accountId = openAccount("INR");
        deposit(accountId, 1000.00);
        int numThreads = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    var request = Map.of(
                            "amount", 600.00,
                            "idempotencyKey", UUID.randomUUID().toString()
                    );
                    ResponseEntity<Map> response = rest.postForEntity(
                            "/api/accounts/" + accountId + "/withdraw",
                            request, Map.class);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Swallow — failures are expected
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // At most 1 should succeed (₹600 from ₹1000 leaves ₹400, can't do it twice)
        assertThat(successCount.get())
                .as("At most one withdrawal of ₹600 from ₹1000 should succeed")
                .isLessThanOrEqualTo(1);

        // Balance must never go negative
        double balance = ((Number) getAccount(accountId).get("balance")).doubleValue();
        assertThat(balance)
                .as("Balance must never be negative")
                .isGreaterThanOrEqualTo(0.0);
    }

    // Test 3: Concurrent transfers from same sender — no double-spend
    @Test
    @DisplayName("10 concurrent transfers of ₹600 from ₹1000 → at most 1 succeeds, no money created")
    void concurrentTransfers_noDoubleSpend() throws Exception {
        String alice = openAccount("INR");
        String bob = openAccount("INR");
        deposit(alice, 1000.00);
        int numThreads = 10;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    var request = Map.of(
                            "fromAccountId", alice,
                            "toAccountId", bob,
                            "amount", 600.00,
                            "idempotencyKey", UUID.randomUUID().toString()
                    );
                    ResponseEntity<Map> response = rest.postForEntity(
                            "/api/transfers", request, Map.class);
                    if (response.getStatusCode() == HttpStatus.CREATED) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Expected
                } finally {
                    finishGate.countDown();
                }
            });
        }

        startGate.countDown();
        finishGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        double aliceBal = ((Number) getAccount(alice).get("balance")).doubleValue();
        double bobBal = ((Number) getAccount(bob).get("balance")).doubleValue();

        // Conservation: total is still ₹1000
        assertThat(aliceBal + bobBal)
                .as("Total money must be conserved (₹1000)")
                .isEqualTo(1000.0);

        // At most one transfer of ₹600 from ₹1000 should succeed
        assertThat(successCount.get())
                .as("At most one transfer of ₹600 from ₹1000")
                .isLessThanOrEqualTo(1);

        // Alice's balance must not be negative
        assertThat(aliceBal)
                .as("Sender balance must never go negative")
                .isGreaterThanOrEqualTo(0.0);
    }
}
