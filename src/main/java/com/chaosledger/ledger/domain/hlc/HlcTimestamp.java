package com.chaosledger.ledger.domain.hlc;

import java.util.Objects;

/**
 * A Hybrid Logical Clock timestamp combining physical wall-clock time
 * with a logical counter and a node identifier.
 *
 * Ordering rules (from the Kulkarni et al. 2014 paper):
 *   1. Compare physicalTime first.
 *   2. If equal, compare logicalCounter.
 *   3. If still equal, compare nodeId (tiebreaker — guarantees total order).
 *
 * This value object is immutable and safe to store, serialize, and compare.
 */
public record HlcTimestamp(
        long physicalTime,
        int logicalCounter,
        String nodeId
) implements Comparable<HlcTimestamp> {

    public HlcTimestamp {
        if (physicalTime < 0) {
            throw new IllegalArgumentException(
                    "physicalTime must be non-negative: " + physicalTime);
        }
        if (logicalCounter < 0) {
            throw new IllegalArgumentException(
                    "logicalCounter must be non-negative: " + logicalCounter);
        }
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }

    /**
     * A zero timestamp — used as the initial state before any tick().
     */
    public static HlcTimestamp zero(String nodeId) {
        return new HlcTimestamp(0, 0, nodeId);
    }

    /**
     * Total ordering: physicalTime → logicalCounter → nodeId.
     * The nodeId comparison is a tiebreaker that ensures no two
     * timestamps from different nodes are ever "equal" in ordering.
     */
    @Override
    public int compareTo(HlcTimestamp other) {
        int cmp = Long.compare(this.physicalTime, other.physicalTime);
        if (cmp != 0) return cmp;

        cmp = Integer.compare(this.logicalCounter, other.logicalCounter);
        if (cmp != 0) return cmp;

        return this.nodeId.compareTo(other.nodeId);
    }

    /**
     * Returns true if this timestamp is strictly before the other.
     */
    public boolean isBefore(HlcTimestamp other) {
        return this.compareTo(other) < 0;
    }

    /**
     * Returns true if this timestamp is strictly after the other.
     */
    public boolean isAfter(HlcTimestamp other) {
        return this.compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return physicalTime + ":" + logicalCounter + "@" + nodeId;
    }
}