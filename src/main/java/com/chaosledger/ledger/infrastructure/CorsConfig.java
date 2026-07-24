package com.chaosledger.ledger.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Week 14 — CORS configuration for the Trust Dashboard.
 *
 * During development, the Vite dev server runs on localhost:5173
 * while Spring Boot runs on localhost:8080. The browser blocks
 * cross-origin requests unless the server explicitly allows them.
 *
 * In production (Docker), nginx reverse-proxies both /api and /ws
 * from the same origin, so CORS isn't needed.
 *
 * This allows ALL origins (*) because:
 *   1. The dashboard is read-only — no mutation endpoints exposed
 *   2. The chaos endpoints are already gated behind chaos.enabled=true
 *   3. Restricting to localhost:5173 breaks Docker-internal calls
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);

        registry.addMapping("/ws/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
