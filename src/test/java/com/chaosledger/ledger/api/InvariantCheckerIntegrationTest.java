package com.chaosledger.ledger.api;

import com.chaosledger.ledger.IntegrationTestBase;
import com.chaosledger.ledger.infrastructure.invariants.InvariantCheckerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Invariant Checker.
 *
 * These tests:
 * 1. Create accounts and perform transfers via the REST API
 * 2. Manually trigger the invariant checker (don't wait for scheduler)
 * 3. Hit GET /api/invariants and verify all invariants pass
 *
 * This proves the complete pipeline: REST → CommandHandler → EventStore
 * → InvariantChecker → REST response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvariantCheckerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvariantCheckerService checkerService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    @DisplayName("Invariants endpoint returns empty results before any data exists")
    void invariantsEndpoint_beforeData_returnsResults() throws Exception {
        // Trigger a check manually (don't wait for scheduler)
        checkerService.runAllChecks();

        MvcResult result = mockMvc.perform(get("/api/invariants"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());

        assertThat(response.has("status")).isTrue();
        assertThat(response.has("invariants")).isTrue();
        assertThat(response.get("status").get("invariantCount").asInt()).isEqualTo(5);
    }

    @Test
    @Order(2)
    @DisplayName("All invariants pass after creating accounts and transferring money")
    void allInvariantsPass_afterValidOperations() throws Exception {
        // Create two accounts
        String account1Id = createAccount("Alice");
        String account2Id = createAccount("Bob");

        // Deposit money into account 1
        deposit(account1Id, "5000.00");

        // Transfer from account 1 to account 2
        transfer(account1Id, account2Id, "1500.00");

        // Deposit more into account 2
        deposit(account2Id, "2000.00");

        // Withdraw from account 1
        withdraw(account1Id, "500.00");

        // Trigger invariant check
        checkerService.runAllChecks();

        // Verify via REST endpoint
        MvcResult result = mockMvc.perform(get("/api/invariants"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());

        JsonNode status = response.get("status");
        assertThat(status.get("allPassing").asBoolean())
                .as("All invariants should pass")
                .isTrue();
        assertThat(status.get("failed").asInt()).isEqualTo(0);
        assertThat(status.get("errors").asInt()).isEqualTo(0);
        assertThat(status.get("passed").asInt()).isEqualTo(5);

        // Verify each invariant individually
        JsonNode invariants = response.get("invariants");
        assertThat(invariants.size()).isEqualTo(5);

        for (JsonNode inv : invariants) {
            assertThat(inv.get("status").asText())
                    .as("Invariant '%s' should pass", inv.get("name").asText())
                    .isEqualTo("PASSED");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Invariants pass after multiple transfers between accounts")
    void invariantsPass_afterMultipleTransfers() throws Exception {
        String acc1 = createAccount("Charlie");
        String acc2 = createAccount("Diana");
        String acc3 = createAccount("Eve");

        deposit(acc1, "10000.00");
        deposit(acc2, "5000.00");
        deposit(acc3, "3000.00");

        // Chain of transfers
        transfer(acc1, acc2, "2000.00");
        transfer(acc2, acc3, "1500.00");
        transfer(acc3, acc1, "500.00");
        transfer(acc1, acc3, "1000.00");

        // Trigger and verify
        checkerService.runAllChecks();

        MvcResult result = mockMvc.perform(get("/api/invariants"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode status = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("status");

        assertThat(status.get("allPassing").asBoolean()).isTrue();
        assertThat(status.get("eventsScanned").asLong())
                .as("Should have scanned many events")
                .isGreaterThan(0);
    }

    // Helper methods

    private String createAccount(String ownerName) throws Exception {
        String body = """
                {
                    "ownerId": "%s",
                    "currency": "INR"
                }
                """.formatted(UUID.randomUUID());

        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());
        return response.get("accountId").asText();
    }

    private void deposit(String accountId, String amount) throws Exception {
        String body = """
                {
                    "amount": %s,
                    "idempotencyKey": "%s"
                }
                """.formatted(amount, UUID.randomUUID());

        mockMvc.perform(post("/api/accounts/" + accountId + "/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void withdraw(String accountId, String amount) throws Exception {
        String body = """
                {
                    "amount": %s,
                    "idempotencyKey": "%s"
                }
                """.formatted(amount, UUID.randomUUID());

        mockMvc.perform(post("/api/accounts/" + accountId + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void transfer(String fromId, String toId, String amount) throws Exception {
        String body = """
                {
                    "fromAccountId": "%s",
                    "toAccountId": "%s",
                    "amount": %s,
                    "idempotencyKey": "%s"
                }
                """.formatted(fromId, toId, amount, UUID.randomUUID());

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
