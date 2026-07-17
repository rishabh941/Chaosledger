package com.chaosledger.ledger.api;

import com.chaosledger.ledger.infrastructure.invariants.InvariantCheckerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST endpoint for the invariant checker.
 *
 * GET  /api/invariants     → full status + all invariant results
 * POST /api/invariants/run → force a synchronous check cycle (added Week 10)
 *
 * The POST endpoint was added in Week 10 so multi-node integration tests do
 * not have to wait ~15 seconds for the scheduler's initial-delay window. The
 * scheduler still runs every 10 seconds; the POST just triggers an extra run
 * on demand.
 */
@RestController
@RequestMapping("/api/invariants")
@RequiredArgsConstructor
public class InvariantController {

    private final InvariantCheckerService checkerService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getInvariants() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", checkerService.getStatus());
        response.put("invariants", checkerService.getLatestResults());
        return ResponseEntity.ok(response);
    }

    /**
     * Run the invariant check cycle synchronously and return the fresh results.
     * Multi-node integration tests call this after issuing writes so the assertion
     * can inspect the just-computed results without racing the 10-second scheduler.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runNow() {
        checkerService.runAllChecks();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", checkerService.getStatus());
        response.put("invariants", checkerService.getLatestResults());
        return ResponseEntity.ok(response);
    }
}