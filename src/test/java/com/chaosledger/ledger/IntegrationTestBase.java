package com.chaosledger.ledger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.TimeZone;

@SpringBootTest
public abstract class IntegrationTestBase {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        System.setProperty("user.timezone", "UTC");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        registry.add(
                "spring.datasource.url",
                () -> "jdbc:postgresql://localhost:5432/chaosledger_test"
        );

        registry.add(
                "spring.datasource.username",
                () -> "postgres"
        );

        registry.add(
                "spring.datasource.password",
                () -> "postgres"
        );

        registry.add(
                "spring.flyway.clean-disabled",
                () -> "false"
        );
    }
}