package com.chaosledger.ledger.api;

import com.chaosledger.ledger.domain.invariants.InvariantResult;
import com.chaosledger.ledger.infrastructure.invariants.InvariantCheckerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the invariant checker.
 *
 * GET /api/invariants → full status + all invariant results
 *
 * Response shape:
 * {
 *   "status": {
 *     "invariantCount": 5,
 *     "lastRunAt": "2026-07-04T10:30:00Z",
 *     "eventsScanned": 47,
 *     "passed": 5,
 *     "failed": 0,
 *     "errors": 0,
 *     "allPassing": true
 *   },
 *   "invariants": [
 *     {
 *       "name": "conservation-of-money",
 *       "status": "PASSED",
 *       "message": "All checks passed",
 *       "checkedAt": "2026-07-04T10:30:00Z",
 *       "duration": "PT0.003S",
 *       "lastViolation": null
 *     },
 *     ...
 *   ]
 * }
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
}