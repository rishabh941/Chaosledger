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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component tests for TransferController.
 *
 * Transfers are the most complex operation because they touch TWO aggregates
 * within a single transaction. These tests verify double-entry bookkeeping:
 * money debited from sender must equal money credited to receiver.
 */
class TransferControllerTest extends ComponentTestBaseFallback {

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
        var response = rest.postForEntity("/api/accounts", request, Map.class);
        return response.getBody().get("accountId").toString();
    }

    private void deposit(String accountId, double amount) {
        var request = Map.of(
                "amount", amount,
                "idempotencyKey", UUID.randomUUID().toString()
        );
        rest.postForEntity(
                "/api/accounts/" + accountId + "/deposit", request, Map.class);
    }

    private ResponseEntity<Map> transfer(String from, String to, double amount, String key) {
        var request = Map.of(
                "fromAccountId", from,
                "toAccountId", to,
                "amount", amount,
                "idempotencyKey", key
        );
        return rest.postForEntity("/api/transfers", request, Map.class);
    }

    private Map<String, Object> getAccount(String accountId) {
        return rest.getForObject("/api/accounts/" + accountId, Map.class);
    }

    // Test 1: Basic transfer — happy path
    @Test
    @DisplayName("Transfer ₹300: sender -300, receiver +300, total unchanged")
    void transfer_movesMoneyCorrectly() {
        String alice = openAccount("INR");
        String bob = openAccount("INR");
        deposit(alice, 1000.00);
        deposit(bob, 500.00);

        ResponseEntity<Map> response = transfer(
                alice, bob, 300.00, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("transferId");

        // Verify balances: Alice 1000-300=700, Bob 500+300=800
        assertThat(getAccount(alice).get("balance")).isEqualTo(700.0);
        assertThat(getAccount(bob).get("balance")).isEqualTo(800.0);
    }

    // Test 2: Conservation of money — total before == total after
    @Test
    @DisplayName("Total money in system is conserved across transfers")
    void transfer_conservesMoney() {
        String alice = openAccount("INR");
        String bob = openAccount("INR");
        String charlie = openAccount("INR");
        deposit(alice, 1000.00);
        deposit(bob, 2000.00);
        deposit(charlie, 3000.00);

        double totalBefore = 1000.0 + 2000.0 + 3000.0;

        // Chain of transfers
        transfer(alice, bob, 400.00, UUID.randomUUID().toString());
        transfer(bob, charlie, 700.00, UUID.randomUUID().toString());
        transfer(charlie, alice, 200.00, UUID.randomUUID().toString());

        double aliceBal = ((Number) getAccount(alice).get("balance")).doubleValue();
        double bobBal = ((Number) getAccount(bob).get("balance")).doubleValue();
        double charlieBal = ((Number) getAccount(charlie).get("balance")).doubleValue();
        double totalAfter = aliceBal + bobBal + charlieBal;

        assertThat(totalAfter).isEqualTo(totalBefore);
    }

    // Test 3: Transfer to self → 400
    @Test
    @DisplayName("Transfer to same account → 400 BAD_REQUEST")
    void transfer_toSelf_returns400() {
        String alice = openAccount("INR");
        deposit(alice, 1000.00);

        ResponseEntity<Map> response = transfer(
                alice, alice, 100.00, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("BAD_REQUEST");
    }

    // Test 4: Transfer to nonexistent account → 404
    @Test
    @DisplayName("Transfer to nonexistent receiver → 404")
    void transfer_toNonexistentReceiver_returns404() {
        String alice = openAccount("INR");
        deposit(alice, 1000.00);
        String fakeId = UUID.randomUUID().toString();

        ResponseEntity<Map> response = transfer(
                alice, fakeId, 100.00, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // Test 5: Transfer with insufficient balance → 422
    @Test
    @DisplayName("Transfer more than sender balance → 422 INSUFFICIENT_BALANCE")
    void transfer_insufficientBalance_returns422() {
        String alice = openAccount("INR");
        String bob = openAccount("INR");
        deposit(alice, 100.00);

        ResponseEntity<Map> response = transfer(
                alice, bob, 999.00, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("error")).isEqualTo("INSUFFICIENT_BALANCE");

        // Verify no money moved
        assertThat(getAccount(alice).get("balance")).isEqualTo(100.0);
        assertThat(getAccount(bob).get("balance")).isEqualTo(0.0);
    }

    // Test 6: Duplicate transfer idempotency key → 409
    @Test
    @DisplayName("Same transfer idempotencyKey twice → 409, only first executes")
    void transfer_duplicateKey_returns409() {
        String alice = openAccount("INR");
        String bob = openAccount("INR");
        deposit(alice, 1000.00);
        String key = UUID.randomUUID().toString();

        // First transfer succeeds
        transfer(alice, bob, 300.00, key);

        // Same key again → rejected
        ResponseEntity<Map> dup = transfer(alice, bob, 300.00, key);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Only ONE transfer should have executed
        assertThat(getAccount(alice).get("balance")).isEqualTo(700.0);
        assertThat(getAccount(bob).get("balance")).isEqualTo(300.0);
    }
}
