package com.chaosledger.ledger.api;

import com.chaosledger.ledger.infrastructure.kafka.KafkaEventConsumer;
import com.chaosledger.ledger.infrastructure.kafka.KafkaEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Debug endpoints for Kafka observability and poison-pill injection.
 * Only active when kafka.enabled=true.
 */
@RestController
@RequestMapping("/api/debug/kafka")
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
@RequiredArgsConstructor
public class KafkaDebugController {

    private final KafkaEventPublisher publisher;
    private final KafkaEventConsumer consumer;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("validMessagesConsumed", consumer.getValidCount());
        body.put("invalidMessagesConsumed", consumer.getInvalidCount());
        body.put("messagesRoutedToDlt", consumer.getDltCount());
        return ResponseEntity.ok(body);
    }

    /**
     * Publish a raw string to the ledger-events topic. Used by scenario
     * 5.3 to inject a poison pill without needing a Kafka client in tests.
     */
    @PostMapping("/publish-raw")
    public ResponseEntity<Map<String, Object>> publishRaw(@RequestBody String rawBody) {
        String key = UUID.randomUUID().toString();
        publisher.publishRaw(key, rawBody);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("published", true);
        body.put("key", key);
        body.put("topic", KafkaEventPublisher.TOPIC);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reset-counters")
    public ResponseEntity<Map<String, Object>> resetCounters() {
        consumer.resetCounters();
        return ResponseEntity.ok(Map.of("reset", true));
    }
}
