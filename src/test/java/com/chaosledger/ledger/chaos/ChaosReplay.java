// src/test/java/com/chaosledger/ledger/chaos/ChaosReplay.java
package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week 13 — Basic chaos replay.
 *
 * Records a chaos run as JSON (exactly what ChaosEngine.getEventLog()
 * already produces) and replays that sequence of network-fault actions
 * against a live cluster.
 *
 * This is deliberately "basic": it replays ChaosEngine ACTIONS in order
 * with the original inter-event timing, not a fully deterministic
 * replay of message ordering, RNG seeds, and timer firings. True
 * deterministic replay is Week 17.
 *
 * Good for: reproducing a chaos run's network-fault sequence to confirm
 *   a bug isn't a one-off flake, or sharing a failing sequence without
 *   re-explaining the steps by hand.
 * NOT a guarantee of: identical Raft election outcomes, identical HLC
 *   timestamps, or identical event ordering — those depend on real
 *   network timing that isn't seeded here.
 */
public class ChaosReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Every ChaosEngine description starts with "Node <n> ..."
    private static final Pattern NODE_ID = Pattern.compile("Node (\\d+)");

    private ChaosReplay() {}

    // ── Save / load ─────────────────────────────────────────────

    public static void save(List<ChaosEngine.ChaosEvent> events, Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writeValueAsString(events));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save chaos event log to " + file, e);
        }
    }

    public static List<ChaosEngine.ChaosEvent> load(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file),
                    MAPPER.getTypeFactory()
                            .constructCollectionType(List.class, ChaosEngine.ChaosEvent.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load chaos event log from " + file, e);
        }
    }

    // ── Replay ──────────────────────────────────────────────────

    /**
     * Replay a recorded event log against a live ChaosEngine, preserving
     * the original spacing between actions (capped at maxGapMs).
     */
    public static void replay(ChaosEngine engine, List<ChaosEngine.ChaosEvent> events,
                              long maxGapMs) {
        Instant previous = null;
        for (ChaosEngine.ChaosEvent event : events) {
            if (previous != null) {
                long gap = Duration.between(previous, event.timestamp()).toMillis();
                sleepQuietly(Math.max(0, Math.min(gap, maxGapMs)));
            }
            apply(engine, event);
            previous = event.timestamp();
        }
    }

    private static void apply(ChaosEngine engine, ChaosEngine.ChaosEvent event) {
        switch (event.action()) {
            case "SETUP" -> { /* proxies already exist on a live cluster */ }
            case "PARTITION" -> engine.partitionNode(extractNodeId(event.description()));
            case "PARTITION_FULL" -> engine.partitionNodeFull(extractNodeId(event.description()));
            case "HEAL_PARTITION" -> engine.healPartition(extractNodeId(event.description()));
            case "SLOW_NETWORK" -> engine.slowNetwork(extractNodeId(event.description()), 200, 50);
            case "HEAL_SLOW" -> engine.healSlowNetwork(extractNodeId(event.description()));
            case "THROTTLE" -> engine.throttleBandwidth(extractNodeId(event.description()), 1);
            case "HEAL_THROTTLE" -> engine.healThrottle(extractNodeId(event.description()));
            case "DROP_CONNECTIONS" -> engine.dropConnections(extractNodeId(event.description()), 0);
            case "HEAL_DROP" -> engine.healDropConnections(extractNodeId(event.description()));
            case "FLAPPING" -> engine.flappingNetwork(extractNodeId(event.description()));
            case "HEAL_FLAPPING" -> engine.healFlapping(extractNodeId(event.description()));
            case "ASYMMETRIC_PARTITION" -> engine.partitionAsymmetric(
                    extractNodeId(event.description()),
                    event.description().contains("cannot receive")
                            ? ChaosEngine.AsymmetricDirection.CANNOT_RECEIVE
                            : ChaosEngine.AsymmetricDirection.CANNOT_SEND);
            case "HEAL_ASYMMETRIC" -> engine.healAsymmetricPartition(extractNodeId(event.description()));
            case "HEAL_ALL" -> engine.healAll();
            default -> throw new IllegalStateException(
                    "Unknown chaos action in replay log: " + event.action());
        }
    }

    private static int extractNodeId(String description) {
        Matcher m = NODE_ID.matcher(description);
        if (!m.find()) {
            throw new IllegalStateException(
                    "Could not find a node id in chaos event description: " + description);
        }
        return Integer.parseInt(m.group(1));
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}