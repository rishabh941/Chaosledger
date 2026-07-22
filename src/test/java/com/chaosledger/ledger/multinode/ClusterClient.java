package com.chaosledger.ledger.multinode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for interacting with a 3-node ChaosLedger cluster in tests.
 *
 * Responsibilities:
 *   - Discover the current leader by polling /api/raft/status
 *   - Route all writes (open, deposit, withdraw, transfer) to the leader
 *   - Retry a write once if the leader changed between discovery and submission
 *   - Read from any specified node (leader or follower)
 *   - Provide convenience getters for HLC status, Raft status, invariants, and
 *     the debug /events endpoint
 */
public class ClusterClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<String> nodeUrls;
    private final HttpClient http;
    private volatile int cachedLeaderIdx = -1;

    public ClusterClient(List<String> nodeUrls) {
        if (nodeUrls == null || nodeUrls.size() < 3) {
            throw new IllegalArgumentException(
                    "Cluster client requires at least 3 node URLs; got: " + nodeUrls);
        }
        this.nodeUrls = List.copyOf(nodeUrls);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── Node addressing ─────────────────────────────────────────────

    public int nodeCount() { return nodeUrls.size(); }
    public String nodeUrl(int idx) { return nodeUrls.get(idx); }

    // ── Leader discovery ────────────────────────────────────────────

    public int waitForLeaderElection(Duration timeout) {
        int[] holder = new int[]{-1};
        Awaitility.await("leader election")
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> {
                    int idx = findLeaderOrMinusOne();
                    if (idx >= 0) { holder[0] = idx; return true; }
                    return false;
                });
        cachedLeaderIdx = holder[0];
        return holder[0];
    }

    public int findLeaderOrMinusOne() {
        for (int i = 0; i < nodeUrls.size(); i++) {
            try {
                JsonNode status = getJson(i, "/api/raft/status");
                if (status != null && "LEADER".equals(status.path("role").asText(""))) {
                    return i;
                }
            } catch (Exception ignored) { }
        }
        return -1;
    }

    public int findLeader() {
        int idx = findLeaderOrMinusOne();
        if (idx < 0) {
            throw new IllegalStateException(
                    "No leader elected across " + nodeUrls.size() + " nodes");
        }
        cachedLeaderIdx = idx;
        return idx;
    }

    // ── Write operations (always via leader) ────────────────────────

    public UUID openAccount(UUID ownerId, String currency) {
        Map<String, Object> body = Map.of(
                "ownerId", ownerId.toString(),
                "currency", currency);
        JsonNode resp = postJsonToLeader("/api/accounts", body, 201);
        return UUID.fromString(resp.path("accountId").asText());
    }

    public void deposit(UUID accountId, BigDecimal amount, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "amount", amount.toPlainString(),
                "idempotencyKey", idempotencyKey.toString());
        postJsonToLeader("/api/accounts/" + accountId + "/deposit", body, 200);
    }

    public void withdraw(UUID accountId, BigDecimal amount, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "amount", amount.toPlainString(),
                "idempotencyKey", idempotencyKey.toString());
        postJsonToLeader("/api/accounts/" + accountId + "/withdraw", body, 200);
    }

    public UUID transfer(UUID fromId, UUID toId, BigDecimal amount, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "fromAccountId", fromId.toString(),
                "toAccountId", toId.toString(),
                "amount", amount.toPlainString(),
                "idempotencyKey", idempotencyKey.toString());
        JsonNode resp = postJsonToLeader("/api/transfers", body, 201);
        return UUID.fromString(resp.path("transferId").asText());
    }

    // ── Read operations (any node) ──────────────────────────────────

    public JsonNode getAccount(int nodeIdx, UUID accountId) {
        return getJson(nodeIdx, "/api/accounts/" + accountId);
    }

    public BigDecimal getBalance(int nodeIdx, UUID accountId) {
        JsonNode node = getAccount(nodeIdx, accountId);
        return new BigDecimal(node.path("balance").asText("0"));
    }

    public JsonNode getEvents(int nodeIdx, UUID accountId) {
        return getJson(nodeIdx, "/api/accounts/" + accountId + "/events");
    }

    public long getEventCount(int nodeIdx) {
        JsonNode node = getJson(nodeIdx, "/api/debug/events/count");
        return node.path("count").asLong(-1);
    }

    public List<Map<String, Object>> getDebugEvents(int nodeIdx, int limit) {
        JsonNode node = getJson(nodeIdx, "/api/debug/events?limit=" + limit);
        return MAPPER.convertValue(node, new TypeReference<>() {});
    }

    public JsonNode getRaftStatus(int nodeIdx) { return getJson(nodeIdx, "/api/raft/status"); }
    public JsonNode getHlcStatus(int nodeIdx) { return getJson(nodeIdx, "/api/hlc/status"); }
    public JsonNode getInvariants(int nodeIdx) { return getJson(nodeIdx, "/api/invariants"); }

    public JsonNode runInvariants(int nodeIdx) {
        return postJson(nodeIdx, "/api/invariants/run", Map.of(), 200);
    }

    // ── Convenience assertions helpers ──────────────────────────────

    public void waitForBalance(int nodeIdx, UUID accountId,
                               BigDecimal expected, Duration timeout) {
        Awaitility.await("balance on node " + nodeIdx + " == " + expected)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    BigDecimal actual = getBalance(nodeIdx, accountId);
                    if (actual.compareTo(expected) != 0) {
                        throw new AssertionError("Expected balance " + expected
                                + " but saw " + actual + " on node " + nodeIdx);
                    }
                });
    }

    public void waitForEventCount(int nodeIdx, long expected, Duration timeout) {
        Awaitility.await("event count on node " + nodeIdx + " >= " + expected)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    long actual = getEventCount(nodeIdx);
                    if (actual < expected) {
                        throw new AssertionError("Expected >= " + expected
                                + " events but saw " + actual + " on node " + nodeIdx);
                    }
                });
    }

    // ── Low-level HTTP ──────────────────────────────────────────────

    private JsonNode postJsonToLeader(String path, Map<String, Object> body, int expectedStatus) {
        int leaderIdx = cachedLeaderIdx >= 0 ? cachedLeaderIdx : findLeader();
        try {
            return postJson(leaderIdx, path, body, expectedStatus);
        } catch (RuntimeException e) {
            cachedLeaderIdx = -1;
            int fresh = findLeader();
            return postJson(fresh, path, body, expectedStatus);
        }
    }

    private JsonNode postJson(int nodeIdx, String path,
                              Map<String, Object> body, int expectedStatus) {
        String url = nodeUrls.get(nodeIdx) + path;
        String json = writeJson(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = send(req);

        if (resp.statusCode() != expectedStatus) {
            throw new RuntimeException("POST " + url + " returned " + resp.statusCode()
                    + " (expected " + expectedStatus + "). Body: " + resp.body());
        }
        return readJson(resp.body());
    }

    private JsonNode getJson(int nodeIdx, String path) {
        String url = nodeUrls.get(nodeIdx) + path;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("GET " + url + " returned "
                    + resp.statusCode() + ". Body: " + resp.body());
        }
        return readJson(resp.body());
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("HTTP call failed: "
                    + req.method() + " " + req.uri() + " → " + e.getMessage(), e);
        }
    }

    private static String writeJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("JSON serialize failed", e); }
    }

    private static JsonNode readJson(String body) {
        try {
            return body == null || body.isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("JSON parse failed: " + body, e);
        }
    }

    public void setClockOffset(int nodeIdx, long offsetMillis) {
        postJson(nodeIdx, "/api/debug/clock-offset", Map.of("offsetMillis", offsetMillis), 200);
    }

    public long getClockOffset(int nodeIdx) {
        JsonNode node = getJson(nodeIdx, "/api/debug/clock-offset");
        return node.path("offsetMillis").asLong(0);
    }

    /** Writes directly to a specific node, bypassing leader auto-discovery. */
    public void depositDirect(int nodeIdx, UUID accountId, BigDecimal amount, UUID idempotencyKey) {
        Map<String, Object> body = Map.of(
                "amount", amount.toPlainString(),
                "idempotencyKey", idempotencyKey.toString());
        postJson(nodeIdx, "/api/accounts/" + accountId + "/deposit", body, 200);
    }

    public Map<String, Object> getKafkaStats(int nodeIdx) {
        JsonNode node = getJson(nodeIdx, "/api/debug/kafka/stats");
        return MAPPER.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    public void publishRawToKafka(int nodeIdx, String rawBody) {
        // POST raw string — the endpoint accepts text/plain
        String url = nodeUrls.get(nodeIdx) + "/api/debug/kafka/publish-raw";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("publish-raw failed: HTTP " + resp.statusCode());
        }
    }

    public void resetKafkaCounters(int nodeIdx) {
        postJson(nodeIdx, "/api/debug/kafka/reset-counters", Map.of(), 200);
    }

    // ── Debug helpers ───────────────────────────────────────────────

    public Map<String, Object> snapshotAllNodes() {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (int i = 0; i < nodeUrls.size(); i++) {
            try {
                snap.put("node-" + (i + 1), Map.of(
                        "url", nodeUrls.get(i),
                        "raft", getRaftStatus(i),
                        "hlc", getHlcStatus(i),
                        "events", getEventCount(i)));
            } catch (Exception e) {
                snap.put("node-" + (i + 1), Map.of("error", e.getMessage()));
            }
        }
        return snap;
    }
}