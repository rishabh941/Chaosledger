package com.chaosledger.ledger.infrastructure.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Week 14 — WebSocket configuration for the Trust Dashboard.
 *
 * The dashboard connects to /ws and subscribes to:
 *   /topic/dashboard  — cluster state pushed every 2 seconds
 *   /topic/logs       — streaming log entries (future)
 *
 * SockJS is enabled as a fallback for environments where raw
 * WebSocket isn't available (corporate proxies, older browsers).
 *
 * In production, replace enableSimpleBroker with a RabbitMQ or
 * Redis-backed broker for horizontal scaling.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory broker for /topic/* destinations
        config.enableSimpleBroker("/topic");
        // Prefix for messages FROM the client (unused for now, but required)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // Vite dev server, Docker, etc.
                .withSockJS();                  // SockJS fallback
    }
}
