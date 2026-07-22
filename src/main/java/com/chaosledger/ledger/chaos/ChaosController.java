// src/main/java/com/chaosledger/ledger/chaos/ChaosController.java
package com.chaosledger.ledger.chaos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for the Chaos Engine.
 *
 * Only active when chaos.enabled=true (set in the chaos Docker Compose
 * profiles). In normal mode this controller does not exist.
 *
 * This endpoint is NOT for tests — tests call ChaosEngine directly.
 * This endpoint is for:
 *   - Manual experimentation from the command line
 *   - The Trust Dashboard (Week 14) to trigger chaos scenarios
 *   - Demo recordings
 *
 * Endpoints:
 *   GET  /api/chaos/status             → Engine status + event log
 *   POST /api/chaos/partition/{nodeId}  → Partition a node
 *   POST /api/chaos/slow/{nodeId}       → Inject latency
 *   POST /api/chaos/heal               → Heal all faults
 */
@RestController
@RequestMapping("/api/chaos")
@ConditionalOnProperty(name = "chaos.enabled", havingValue = "true")
@Slf4j
public class ChaosController {

    private final ChaosEngine chaosEngine;

    public ChaosController(
            @Value("${chaos.toxiproxy.url:http://toxiproxy:8474}") String toxiproxyUrl) {
        ToxiproxyClient toxiClient = new ToxiproxyClient(toxiproxyUrl);
        this.chaosEngine = new ChaosEngine(toxiClient);
        log.info("ChaosController active — Toxiproxy at {}", toxiproxyUrl);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", chaosEngine.isReady());
        body.put("eventLog", chaosEngine.getEventLog());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/partition/{nodeId}")
    public ResponseEntity<Map<String, String>> partition(@PathVariable int nodeId) {
        chaosEngine.partitionNode(nodeId);
        return ResponseEntity.ok(Map.of("action", "partitioned",
                "node", String.valueOf(nodeId)));
    }

    @PostMapping("/partition-full/{nodeId}")
    public ResponseEntity<Map<String, String>> partitionFull(@PathVariable int nodeId) {
        chaosEngine.partitionNodeFull(nodeId);
        return ResponseEntity.ok(Map.of("action", "fully_partitioned",
                "node", String.valueOf(nodeId)));
    }

    @PostMapping("/slow/{nodeId}")
    public ResponseEntity<Map<String, String>> slow(
            @PathVariable int nodeId,
            @RequestParam(defaultValue = "500") long latencyMs,
            @RequestParam(defaultValue = "100") long jitterMs) {
        chaosEngine.slowNetwork(nodeId, latencyMs, jitterMs);
        return ResponseEntity.ok(Map.of("action", "slow_network",
                "node", String.valueOf(nodeId),
                "latencyMs", String.valueOf(latencyMs)));
    }

    @PostMapping("/flapping/{nodeId}")
    public ResponseEntity<Map<String, String>> flapping(@PathVariable int nodeId) {
        chaosEngine.flappingNetwork(nodeId);
        return ResponseEntity.ok(Map.of("action", "flapping",
                "node", String.valueOf(nodeId)));
    }

    @PostMapping("/heal")
    public ResponseEntity<Map<String, String>> heal() {
        chaosEngine.healAll();
        return ResponseEntity.ok(Map.of("action", "healed"));
    }

    @PostMapping("/heal/{nodeId}")
    public ResponseEntity<Map<String, String>> healNode(@PathVariable int nodeId) {
        chaosEngine.healPartition(nodeId);
        return ResponseEntity.ok(Map.of("action", "healed",
                "node", String.valueOf(nodeId)));
    }
}