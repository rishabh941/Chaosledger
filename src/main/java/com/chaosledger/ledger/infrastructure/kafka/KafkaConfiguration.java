// src/main/java/com/chaosledger/ledger/infrastructure/kafka/KafkaConfiguration.java
package com.chaosledger.ledger.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka beans — only active when kafka.enabled=true.
 *
 * Topics are auto-created by Spring's KafkaAdmin if they don't exist.
 * Spring Boot auto-configures KafkaTemplate from application.properties
 * (spring.kafka.bootstrap-servers, etc.), so we don't wire that manually.
 */
@Configuration
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
@Slf4j
public class KafkaConfiguration {

    @Bean
    public NewTopic ledgerEventsTopic() {
        // 3 partitions, RF=1 (single-broker dev setup)
        return new NewTopic(KafkaEventPublisher.TOPIC, 3, (short) 1);
    }

    @Bean
    public NewTopic ledgerEventsDltTopic() {
        return new NewTopic(KafkaEventConsumer.DLT_TOPIC, 1, (short) 1);
    }

    @Bean
    public KafkaEventPublisher kafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        log.info("Kafka event publisher created — events will be published "
                + "to topic '{}'", KafkaEventPublisher.TOPIC);
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    @Bean
    public KafkaEventConsumer kafkaEventConsumer(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate) {
        log.info("Kafka event consumer created — listening on topic '{}', "
                + "DLT on '{}'", KafkaEventPublisher.TOPIC, KafkaEventConsumer.DLT_TOPIC);
        return new KafkaEventConsumer(objectMapper, kafkaTemplate);
    }
}