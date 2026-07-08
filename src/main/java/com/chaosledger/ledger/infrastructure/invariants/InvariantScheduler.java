package com.chaosledger.ledger.infrastructure.invariants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers invariant checks on a fixed schedule.
 *
 * Why a separate class (not just @Scheduled on the service)?
 *   Separation of concerns: InvariantCheckerService knows HOW to
 *   check invariants. This class knows WHEN. In Phase 3, you might
 *   want to trigger checks on leader election events, not just
 *   on a timer. Keeping the trigger separate makes that easy.
 *
 * The interval is configurable via application.properties:
 *   invariant.checker.interval-ms=10000
 *
 * initialDelay=15000 gives the app 15 seconds to start up and
 * run Flyway migrations before the first check.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvariantScheduler {

    private final InvariantCheckerService checkerService;

    @Scheduled(
            fixedDelayString = "${invariant.checker.interval-ms:10000}",
            initialDelayString = "${invariant.checker.initial-delay-ms:15000}"
    )
    public void scheduledCheck() {
        checkerService.runAllChecks();
    }
}