package com.chaosledger.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FALLBACK component test base — use this INSTEAD of ComponentTestBase
 * if Testcontainers doesn't work on your Docker Desktop setup.
 *
 * This connects directly to a running PostgreSQL on localhost:5432.
 * You must have:
 *   1. PostgreSQL running (docker run -d --name pg16 -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16)
 *   2. The test database created (docker exec -it pg16 psql -U postgres -c "CREATE DATABASE chaosledger_test;")
 *
 * To use: change "extends ComponentTestBase" to "extends ComponentTestBaseFallback"
 * in any test class that fails with Testcontainers errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ComponentTestBaseFallback {

    static {
        // Fix the Asia/Calcutta timezone bug on Windows
        System.setProperty("user.timezone", "UTC");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:5432/chaosledger_test");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.clean-disabled", () -> "false");
    }
}
