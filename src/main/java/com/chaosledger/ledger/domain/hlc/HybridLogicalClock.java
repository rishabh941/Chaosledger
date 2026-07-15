package com.chaosledger.ledger.domain.hlc;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Hybrid Logical Clock implementation based on Kulkarni et al. (2014)
 * "Logical Physical Clocks and Consistent Snapshots in Globally
 * Distributed Databases."
 *
 * Two operations:
 *   tick()            — called on local events (before a write)
 *   update(received)  — called when receiving a message from another node
 *
 * Guarantees:
 *   1. tick() always returns a timestamp strictly greater than the previous one.
 *   2. update(received) returns a timestamp greater than both the local
 *      state AND the received timestamp, preserving causal ordering.
 *   3. physicalTime stays close to wall-clock time — the logical counter
 *      resets to 0 whenever the wall clock advances.
 *
 * Thread safety: All public methods are synchronized. In a Spring Boot
 * application with a single HLC bean per node, this is sufficient.
 */
public class HybridLogicalClock {

    private final String nodeId;
    private final LongSupplier wallClock;

    private long lastPhysicalTime;
    private int lastLogicalCounter;

    /**
     * Production constructor — uses System.currentTimeMillis().
     */
    public HybridLogicalClock(String nodeId) {
        this(nodeId, System::currentTimeMillis);
    }

    /**
     * Test constructor — accepts a custom clock source for deterministic testing.
     */
    public HybridLogicalClock(String nodeId, LongSupplier wallClock) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.lastPhysicalTime = 0;
        this.lastLogicalCounter = 0;
    }

    /**
     * Generate a new timestamp for a local event.
     *
     * Algorithm (Kulkarni et al. 2014, Section 3):
     *   pt' = max(lastPhysicalTime, wallClock)
     *   if pt' == lastPhysicalTime:
     *       logicalCounter = lastLogicalCounter + 1
     *   else:
     *       logicalCounter = 0
     *   lastPhysicalTime = pt'
     *   lastLogicalCounter = logicalCounter
     *   return (pt', logicalCounter, nodeId)
     */
    public synchronized HlcTimestamp tick() {
        long now = wallClock.getAsLong();
        long newPhysical = Math.max(lastPhysicalTime, now);

        int newLogical;
        if (newPhysical == lastPhysicalTime) {
            newLogical = lastLogicalCounter + 1;
        } else {
            newLogical = 0;
        }

        lastPhysicalTime = newPhysical;
        lastLogicalCounter = newLogical;

        return new HlcTimestamp(newPhysical, newLogical, nodeId);
    }

    /**
     * Update the local clock upon receiving a timestamp from another node.
     *
     * Algorithm (Kulkarni et al. 2014, Section 3):
     *   pt' = max(lastPhysicalTime, received.physicalTime, wallClock)
     *   if pt' == lastPhysicalTime == received.physicalTime:
     *       logicalCounter = max(lastLogicalCounter, received.logicalCounter) + 1
     *   else if pt' == lastPhysicalTime:
     *       logicalCounter = lastLogicalCounter + 1
     *   else if pt' == received.physicalTime:
     *       logicalCounter = received.logicalCounter + 1
     *   else:
     *       logicalCounter = 0
     *   lastPhysicalTime = pt'
     *   lastLogicalCounter = logicalCounter
     *   return (pt', logicalCounter, nodeId)
     */
    public synchronized HlcTimestamp update(HlcTimestamp received) {
        Objects.requireNonNull(received, "received timestamp must not be null");

        long now = wallClock.getAsLong();
        long newPhysical = Math.max(
                Math.max(lastPhysicalTime, received.physicalTime()), now);

        int newLogical;
        if (newPhysical == lastPhysicalTime
                && newPhysical == received.physicalTime()) {
            newLogical = Math.max(lastLogicalCounter,
                    received.logicalCounter()) + 1;
        } else if (newPhysical == lastPhysicalTime) {
            newLogical = lastLogicalCounter + 1;
        } else if (newPhysical == received.physicalTime()) {
            newLogical = received.logicalCounter() + 1;
        } else {
            newLogical = 0;
        }

        lastPhysicalTime = newPhysical;
        lastLogicalCounter = newLogical;

        return new HlcTimestamp(newPhysical, newLogical, nodeId);
    }

    /**
     * Returns the current HLC state without advancing it.
     * Useful for debugging and status endpoints.
     */
    public synchronized HlcTimestamp current() {
        return new HlcTimestamp(lastPhysicalTime, lastLogicalCounter, nodeId);
    }

    /**
     * Returns this clock's node ID.
     */
    public String getNodeId() {
        return nodeId;
    }
}