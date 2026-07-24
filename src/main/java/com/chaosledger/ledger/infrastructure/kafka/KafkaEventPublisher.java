package com.chaosledger.ledger.infrastructure.kafka;

import com.chaosledger.ledger.domain.events.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes committed events to Kafka after Raft apply.
 *
 * Design decisions:
 *   - Fire-and-forget: Kafka is a downstream projection, not the source
 *     of truth. If publishing fails, the event is still safely in Postgres.
 *   - Key = aggregateId: ensures all events for an account land in the
 *     same partition, preserving per-account ordering.
 *   - Value = full event JSON with type metadata, so the consumer can
 *     deserialize without guessing the schema.
 *   - Failures are logged, never thrown — a Kafka outage must not break
 *     the Raft state machine apply path.
 */
@Slf4j
public class KafkaEventPublisher {

    public static final String TOPIC = "ledger-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(Event event) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventType", event.getClass().getSimpleName());
            envelope.put("eventId", event.eventId().toString());
            envelope.put("aggregateId", event.aggregateId().toString());
            envelope.put("occurredAt", event.occurredAt().toString());
            envelope.put("payload", objectMapper.convertValue(event, Map.class));

            String json = objectMapper.writeValueAsString(envelope);
            String key = event.aggregateId().toString();

            kafkaTemplate.send(TOPIC, key, json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish event {} to Kafka: {}",
                                    event.eventId(), ex.getMessage());
                        } else {
                            log.debug("Published event {} to Kafka partition={} offset={}",
                                    event.eventId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            // Never let Kafka failures break the state machine
            log.warn("Kafka publish failed for event {}: {}",
                    event.eventId(), e.getMessage());
        }
    }

    /**
     * Publish a raw string directly — used by the debug endpoint for
     * poison-pill testing (scenario 5.3). NOT for production use.
     */
    public void publishRaw(String key, String rawJson) {
        kafkaTemplate.send(TOPIC, key, rawJson);
    }
}
