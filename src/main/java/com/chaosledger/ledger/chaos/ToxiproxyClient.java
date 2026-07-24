package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for Toxiproxy's REST API (runs on port 8474).
 *
 * Toxiproxy is a TCP proxy that sits between Raft nodes and lets us
 * inject network failures (latency, partitions, bandwidth limits)
 * without touching the application code.
 *
 * API reference: https://github.com/Shopify/toxiproxy#http-api
 *
 * Thread safety: this class uses JDK 21 HttpClient which is thread-safe.
 * All operations are synchronous and blocking — appropriate for test code.
 */
@Slf4j
public class ToxiproxyClient {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ToxiproxyClient(String toxiproxyUrl) {
        this.baseUrl = toxiproxyUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
    }

    // Proxy management

    /**
     * Create a new proxy. Toxiproxy listens on listenAddress and
     * forwards to upstreamAddress.
     *
     * Example: createProxy("raft-node1", "0.0.0.0:19864", "ledger-1:9864")
     * creates a proxy that forwards port 19864 to ledger-1's Raft port.
     */
    public void createProxy(String name, String listenAddress, String upstreamAddress) {
        Map<String, Object> body = Map.of(
                "name", name,
                "listen", listenAddress,
                "upstream", upstreamAddress,
                "enabled", true);
        String json = toJson(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 201) {
            log.info("Created proxy '{}': {} → {}", name, listenAddress, upstreamAddress);
        } else if (resp.statusCode() == 409) {
            log.info("Proxy '{}' already exists — skipping creation", name);
        } else {
            throw new RuntimeException("Failed to create proxy '" + name
                    + "': HTTP " + resp.statusCode() + " — " + resp.body());
        }
    }

    /**
     * Enable or disable a proxy entirely. Disabled = connection refused
     * (simulates a complete network partition for that path).
     */
    public void setProxyEnabled(String proxyName, boolean enabled) {
        Map<String, Object> body = Map.of("enabled", enabled);
        String json = toJson(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies/" + proxyName))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to " + (enabled ? "enable" : "disable")
                    + " proxy '" + proxyName + "': HTTP " + resp.statusCode()
                    + " — " + resp.body());
        }
        log.info("Proxy '{}' {}", proxyName, enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Delete a proxy entirely (used for cleanup).
     */
    public void deleteProxy(String proxyName) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies/" + proxyName))
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 204 || resp.statusCode() == 200) {
            log.info("Deleted proxy '{}'", proxyName);
        } else if (resp.statusCode() == 404) {
            log.debug("Proxy '{}' not found — already deleted", proxyName);
        } else {
            throw new RuntimeException("Failed to delete proxy '" + proxyName
                    + "': HTTP " + resp.statusCode());
        }
    }

    /**
     * List all proxies currently configured. Returns a map of
     * proxy name → proxy details.
     */
    public Map<String, Object> listProxies() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to list proxies: HTTP " + resp.statusCode());
        }
        return fromJson(resp.body(), new TypeReference<>() {});
    }

    // Toxic management

    /**
     * Add a latency toxic to a proxy. Direction is "upstream" (toward
     * the real server) or "downstream" (toward the client).
     *
     * @param proxyName the proxy to add latency to
     * @param toxicName unique name for this toxic (e.g. "latency-node1")
     * @param latencyMs base latency in milliseconds
     * @param jitterMs  random jitter ± in milliseconds
     * @param direction "upstream" or "downstream"
     */
    public void addLatency(String proxyName, String toxicName,
                           long latencyMs, long jitterMs, String direction) {
        Map<String, Object> body = Map.of(
                "name", toxicName,
                "type", "latency",
                "stream", direction,
                "toxicity", 1.0,
                "attributes", Map.of(
                        "latency", latencyMs,
                        "jitter", jitterMs));

        doAddToxic(proxyName, body);
        log.info("Added latency toxic '{}' to proxy '{}': {}ms ± {}ms ({})",
                toxicName, proxyName, latencyMs, jitterMs, direction);
    }

    /**
     * Add a bandwidth toxic — limits data throughput to the given rate.
     *
     * @param proxyName the proxy to throttle
     * @param toxicName unique name for this toxic
     * @param rateKB    throughput limit in KB/s
     * @param direction "upstream" or "downstream"
     */
    public void addBandwidth(String proxyName, String toxicName,
                             long rateKB, String direction) {
        Map<String, Object> body = Map.of(
                "name", toxicName,
                "type", "bandwidth",
                "stream", direction,
                "toxicity", 1.0,
                "attributes", Map.of("rate", rateKB));

        doAddToxic(proxyName, body);
        log.info("Added bandwidth toxic '{}' to proxy '{}': {} KB/s ({})",
                toxicName, proxyName, rateKB, direction);
    }

    /**
     * Add a timeout toxic — closes connections after the specified timeout.
     * Simulates connection drops.
     *
     * @param proxyName the proxy
     * @param toxicName unique name for this toxic
     * @param timeoutMs timeout in milliseconds (0 = close immediately)
     * @param direction "upstream" or "downstream"
     */
    public void addTimeout(String proxyName, String toxicName,
                           long timeoutMs, String direction) {
        Map<String, Object> body = Map.of(
                "name", toxicName,
                "type", "timeout",
                "stream", direction,
                "toxicity", 1.0,
                "attributes", Map.of("timeout", timeoutMs));

        doAddToxic(proxyName, body);
        log.info("Added timeout toxic '{}' to proxy '{}': {}ms ({})",
                toxicName, proxyName, timeoutMs, direction);
    }

    /**
     * Add a slicer toxic — slices data into small bits with optional delay.
     * Simulates an unstable, flapping network.
     *
     * @param proxyName    the proxy
     * @param toxicName    unique name for this toxic
     * @param avgBytes     average bytes per slice
     * @param delayMicros  delay between slices in microseconds
     * @param direction    "upstream" or "downstream"
     */
    public void addSlicer(String proxyName, String toxicName,
                          int avgBytes, int delayMicros, String direction) {
        Map<String, Object> body = Map.of(
                "name", toxicName,
                "type", "slicer",
                "stream", direction,
                "toxicity", 1.0,
                "attributes", Map.of(
                        "average_size", avgBytes,
                        "size_variation", avgBytes / 2,
                        "delay", delayMicros));

        doAddToxic(proxyName, body);
        log.info("Added slicer toxic '{}' to proxy '{}': ~{} bytes, {} μs delay ({})",
                toxicName, proxyName, avgBytes, delayMicros, direction);
    }

    /**
     * Remove a specific toxic from a proxy.
     */
    public void removeToxic(String proxyName, String toxicName) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies/" + proxyName
                        + "/toxics/" + toxicName))
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 204 || resp.statusCode() == 200) {
            log.info("Removed toxic '{}' from proxy '{}'", toxicName, proxyName);
        } else if (resp.statusCode() == 404) {
            log.debug("Toxic '{}' not found on proxy '{}' — already removed",
                    toxicName, proxyName);
        } else {
            throw new RuntimeException("Failed to remove toxic '" + toxicName
                    + "' from proxy '" + proxyName + "': HTTP " + resp.statusCode());
        }
    }

    /**
     * List all toxics on a specific proxy.
     */
    public List<Map<String, Object>> listToxics(String proxyName) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies/" + proxyName + "/toxics"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to list toxics for proxy '"
                    + proxyName + "': HTTP " + resp.statusCode());
        }
        return fromJson(resp.body(), new TypeReference<>() {});
    }

    // Reset

    /**
     * Reset Toxiproxy — removes all toxics from all proxies and
     * re-enables all proxies. The proxies themselves are NOT deleted.
     * This is the "heal everything" button.
     */
    public void resetAll() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 204 || resp.statusCode() == 200) {
            log.info("Toxiproxy reset — all toxics removed, all proxies re-enabled");
        } else {
            throw new RuntimeException("Failed to reset Toxiproxy: HTTP "
                    + resp.statusCode());
        }
    }

    /**
     * Health check — verifies Toxiproxy is reachable.
     */
    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/version"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }


    private void doAddToxic(String proxyName, Map<String, Object> body) {
        String json = toJson(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/proxies/" + proxyName + "/toxics"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = send(req);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to add toxic to proxy '"
                    + proxyName + "': HTTP " + resp.statusCode()
                    + " — " + resp.body());
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Toxiproxy HTTP call failed: "
                    + req.method() + " " + req.uri() + " → " + e.getMessage(), e);
        }
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { throw new RuntimeException("JSON serialize failed", e); }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new RuntimeException("JSON deserialize failed", e); }
    }
}
