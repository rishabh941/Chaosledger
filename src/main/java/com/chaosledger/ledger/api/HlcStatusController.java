package com.chaosledger.ledger.api;

import com.chaosledger.ledger.domain.hlc.HlcTimestamp;
import com.chaosledger.ledger.domain.hlc.HybridLogicalClock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the current HLC state for debugging and verification.
 * Used during Week 9 verification and later in chaos scenarios
 * (3.1 clock-drift-forward-2s) to observe HLC behavior.
 */
@RestController
@RequestMapping("/api/hlc")
@RequiredArgsConstructor
public class HlcStatusController {

    private final HybridLogicalClock hlc;

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.of("UTC"));

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        HlcTimestamp current = hlc.current();
        long wallClock = System.currentTimeMillis();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", current.nodeId());
        status.put("physicalTime", current.physicalTime());
        status.put("physicalTimeHuman",
                FORMATTER.format(Instant.ofEpochMilli(current.physicalTime())));
        status.put("logicalCounter", current.logicalCounter());
        status.put("wallClock", wallClock);
        status.put("wallClockHuman",
                FORMATTER.format(Instant.ofEpochMilli(wallClock)));
        status.put("drift", current.physicalTime() - wallClock);
        status.put("formatted", current.toString());

        return ResponseEntity.ok(status);
    }
}