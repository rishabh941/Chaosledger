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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component tests for AccountController.
 *
 * These test the FULL stack: HTTP → Controller → CommandHandler → Account → EventStore → PostgreSQL.
 * No mocking. Real database via Testcontainers. Real HTTP via TestRestTemplate.
 *
 * Why component tests matter:
 * Unit tests verify individual classes in isolation.
 * Component tests verify the entire assembled system works end-to-end.
 * They catch wiring bugs, serialization bugs, transaction boundary bugs,
 * and HTTP status mapping bugs that unit tests miss.
 */
class AccountControllerTest extends ComponentTestBaseFallback {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ProcessedCommandRepository processedCommandRepo;

    @Autowired
    private EventRepository eventRepo;

    @BeforeEach
    void cleanDatabase() {
        // Order matters: clear idempotency records first, then events
        processedCommandRepo.deleteAll();
        processedCommandRepo.flush();
        eventRepo.deleteAll();
        eventRepo.flush();
    }

    // Helper methods — keep tests readable by extracting repeated patterns

    /**
     * Opens a new account and returns the accountId UUID string.
     */
    private String openAccount(String currency) {
        var request = Map.of(
                "ownerId", UUID.randomUUID().toString(),
                "currency", currency
        );
        var response = rest.postForEntity("/api/accounts", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("accountId").toString();
    }

    /**
     * Deposits an amount into the given account with a fresh idempotency key.
     * Returns the idempotency key used.
     */
    private String deposit(String accountId, double amount) {
        String key = UUID.randomUUID().toString();
        deposit(accountId, amount, key);
        return key;
    }

    private void deposit(String accountId, double amount, String idempotencyKey) {
        var request = Map.of(
                "amount", amount,
                "idempotencyKey", idempotencyKey
        );
        var response = rest.postForEntity(
                "/api/accounts/" + accountId + "/deposit", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Gets the account and returns the response body as a Map.
     */
    private Map<String, Object> getAccount(String accountId) {
        return rest.getForObject("/api/accounts/" + accountId, Map.class);
    }

    // Test 1: Open account — happy path
    @Test
    @DisplayName("POST /api/accounts → 201 Created with accountId")
    void openAccount_returnsCreatedWithAccountId() {
        var request = Map.of(
                "ownerId", UUID.randomUUID().toString(),
                "currency", "INR"
        );

        ResponseEntity<Map> response = rest.postForEntity("/api/accounts", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("accountId");
        // Verify the Location header points to the new resource
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath())
                .startsWith("/api/accounts/");
    }

    // Test 2: New account starts with zero balance
    @Test
    @DisplayName("GET new account → balance is 0.00, status OPEN, version 1")
    void newAccount_hasZeroBalanceAndOpenStatus() {
        String accountId = openAccount("INR");

        Map<String, Object> account = getAccount(accountId);

        assertThat(account.get("balance")).isEqualTo(0.0);
        assertThat(account.get("currency")).isEqualTo("INR");
        assertThat(account.get("status")).isEqualTo("OPEN");
        assertThat(account.get("version")).isEqualTo(1);
    }

    // Test 3: Deposit increases balance
    @Test
    @DisplayName("Deposit ₹1000 → balance becomes 1000.00, version increments")
    void deposit_increasesBalance() {
        String accountId = openAccount("INR");

        deposit(accountId, 1000.00);

        Map<String, Object> account = getAccount(accountId);
        assertThat(account.get("balance")).isEqualTo(1000.0);
        assertThat(account.get("version")).isEqualTo(2);
    }

    // Test 4: Multiple deposits accumulate correctly
    @Test
    @DisplayName("Three deposits → balance is cumulative sum")
    void multipleDeposits_accumulateCorrectly() {
        String accountId = openAccount("INR");

        deposit(accountId, 500.00);
        deposit(accountId, 300.00);
        deposit(accountId, 200.00);

        Map<String, Object> account = getAccount(accountId);
        assertThat(account.get("balance")).isEqualTo(1000.0);
        assertThat(account.get("version")).isEqualTo(4); // open + 3 deposits
    }

    // Test 5: Withdrawal decreases balance
    @Test
    @DisplayName("Deposit ₹1000 then withdraw ₹400 → balance is ₹600")
    void withdraw_decreasesBalance() {
        String accountId = openAccount("INR");
        deposit(accountId, 1000.00);

        var withdrawRequest = Map.of(
                "amount", 400.00,
                "idempotencyKey", UUID.randomUUID().toString()
        );
        rest.postForEntity(
                "/api/accounts/" + accountId + "/withdraw", withdrawRequest, Map.class);

        Map<String, Object> account = getAccount(accountId);
        assertThat(account.get("balance")).isEqualTo(600.0);
    }

    // Test 6: Withdraw more than balance → 422
    @Test
    @DisplayName("Withdraw more than balance → 422 INSUFFICIENT_BALANCE")
    void withdraw_moreThanBalance_returns422() {
        String accountId = openAccount("INR");
        deposit(accountId, 100.00);

        var request = Map.of(
                "amount", 999.00,
                "idempotencyKey", UUID.randomUUID().toString()
        );
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/accounts/" + accountId + "/withdraw", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error")).isEqualTo("INSUFFICIENT_BALANCE");
        // Verify the error includes diagnostic details
        assertThat(response.getBody()).containsKey("currentBalance");
        assertThat(response.getBody()).containsKey("requestedAmount");
    }

    // Test 7: GET nonexistent account → 404
    @Test
    @DisplayName("GET nonexistent account → 404 ACCOUNT_NOT_FOUND")
    void getAccount_nonexistent_returns404() {
        UUID fakeId = UUID.randomUUID();

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/accounts/" + fakeId, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    // Test 8: Duplicate idempotency key → 409
    @Test
    @DisplayName("Same idempotencyKey twice → 409 DUPLICATE_COMMAND")
    void duplicateIdempotencyKey_returns409() {
        String accountId = openAccount("INR");
        String key = UUID.randomUUID().toString();

        // First deposit succeeds
        deposit(accountId, 500.00, key);

        // Second deposit with SAME key → rejected
        var dupRequest = Map.of(
                "amount", 500.00,
                "idempotencyKey", key
        );
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/accounts/" + accountId + "/deposit", dupRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).isEqualTo("DUPLICATE_COMMAND");

        // Balance should only reflect ONE deposit, not two
        Map<String, Object> account = getAccount(accountId);
        assertThat(account.get("balance")).isEqualTo(500.0);
    }

    // Test 9: GET event history returns all events in order
    @Test
    @DisplayName("GET /api/accounts/{id}/events → returns ordered event history")
    void getEvents_returnsOrderedHistory() {
        String accountId = openAccount("INR");
        deposit(accountId, 1000.00);

        var withdrawRequest = Map.of(
                "amount", 200.00,
                "idempotencyKey", UUID.randomUUID().toString()
        );
        rest.postForEntity(
                "/api/accounts/" + accountId + "/withdraw", withdrawRequest, Map.class);

        ResponseEntity<List> response = rest.getForEntity(
                "/api/accounts/" + accountId + "/events", List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> events = response.getBody();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("eventType")).isEqualTo("AccountOpened");
        assertThat(events.get(1).get("eventType")).isEqualTo("MoneyDeposited");
        assertThat(events.get(2).get("eventType")).isEqualTo("MoneyWithdrawn");
    }

    // Test 10: Validation — missing required fields → 400
    @Test
    @DisplayName("Deposit without amount → 400 VALIDATION_FAILED")
    void deposit_missingAmount_returns400() {
        String accountId = openAccount("INR");

        // Send request missing 'amount' field
        var request = Map.of(
                "idempotencyKey", UUID.randomUUID().toString()
        );
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/accounts/" + accountId + "/deposit", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
