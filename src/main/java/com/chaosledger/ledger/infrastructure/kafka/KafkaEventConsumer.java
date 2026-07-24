package com.chaosledger.ledger.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Kafka consumer that validates incoming events.
 *
 * In a full system, this would maintain read models, projections, or
 * trigger downstream workflows. In ChaosLedger's current scope, it:
 *   1. Deserializes the message
 *   2. Validates required fields (eventType, eventId, aggregateId)
 *   3. Counts valid/invalid messages for observability
 *   4. Routes invalid messages to the dead-letter topic
 *
 * This is enough to make scenario 5.3 real: a poison-pill message
 * must not crash this consumer, and must end up in the DLT.
 */
@Slf4j
public class KafkaEventConsumer {

    public static final String DLT_TOPIC = "ledger-events-dlt";

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final AtomicLong validCount = new AtomicLong(0);
    private final AtomicLong invalidCount = new AtomicLong(0);
    private final AtomicLong dltCount = new AtomicLong(0);

    public KafkaEventConsumer(ObjectMapper objectMapper,
                              KafkaTemplate<String, String> kafkaTemplate) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaEventPublisher.TOPIC,
            groupId = "chaosledger-consumer")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            // Validate required fields
            if (!node.has("eventType") || !node.has("eventId") || !node.has("aggregateId")) {
                throw new IllegalArgumentException(
                        "Missing required fields (eventType, eventId, aggregateId)");
            }

            String eventType = node.path("eventType").asText();
            if (eventType.isBlank()) {
                throw new IllegalArgumentException("eventType is blank");
            }

            validCount.incrementAndGet();
            log.debug("Consumed valid event: type={}, id={}, partition={}, offset={}",
                    eventType, node.path("eventId").asText(),
                    record.partition(), record.offset());

        } catch (Exception e) {
            invalidCount.incrementAndGet();
            log.warn("Poison pill detected at partition={} offset={}: {}. "
                            + "Routing to DLT.",
                    record.partition(), record.offset(), e.getMessage());
            routeToDlt(record, e);
        }
    }

    private void routeToDlt(ConsumerRecord<String, String> record, Exception cause) {
        try {
            // Wrap the original message with error metadata
            String dltPayload = objectMapper.writeValueAsString(java.util.Map.of(
                    "originalTopic", record.topic(),
                    "originalPartition", record.partition(),
                    "originalOffset", record.offset(),
                    "originalKey", record.key() != null ? record.key() : "",
                    "originalValue", record.value() != null ? record.value() : "",
                    "error", cause.getMessage(),
                    "routedAt", java.time.Instant.now().toString()));

            kafkaTemplate.send(DLT_TOPIC, record.key(), dltPayload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to route poison pill to DLT: {}", ex.getMessage());
                        } else {
                            dltCount.incrementAndGet();
                            log.info("Poison pill routed to DLT: partition={} offset={}",
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("DLT routing itself failed: {}", e.getMessage());
        }
    }

    // Stats for debug endpoint and scenario verification

    public long getValidCount() { return validCount.get(); }
    public long getInvalidCount() { return invalidCount.get(); }
    public long getDltCount() { return dltCount.get(); }

    public void resetCounters() {
        validCount.set(0);
        invalidCount.set(0);
        dltCount.set(0);
    }
}
