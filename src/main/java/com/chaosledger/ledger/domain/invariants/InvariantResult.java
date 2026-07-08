package com.chaosledger.ledger.domain.invariants;

import java.time.Duration;
import java.time.Instant;

public record InvariantResult(
        String name,
        InvariantStatus status,
        String message,
        Instant checkedAt,
        Duration duration,
        String lastViolation
) {

    public static InvariantResult passed(String name, Duration duration) {
        return new InvariantResult(
                name,
                InvariantStatus.PASSED,
                "All checks passed",
                Instant.now(),
                duration,
                null
        );
    }

    public static InvariantResult failed(String name, String message, Duration duration) {
        return new InvariantResult(
                name,
                InvariantStatus.FAILED,
                message,
                Instant.now(),
                duration,
                message
        );
    }

    public static InvariantResult error(String name, String errorMessage, Duration duration) {
        return new InvariantResult(
                name,
                InvariantStatus.ERROR,
                errorMessage,
                Instant.now(),
                duration,
                null
        );
    }
}