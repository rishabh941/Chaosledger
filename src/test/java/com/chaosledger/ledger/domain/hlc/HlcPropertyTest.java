package com.chaosledger.ledger.domain.hlc;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for HybridLogicalClock.
 * These run 1000+ random scenarios per property to verify
 * the HLC guarantees hold under all conditions.
 */
class HlcPropertyTest {

    // Property 1: tick() is always strictly monotonic

    @Property(tries = 1000)
    void tickIsStrictlyMonotonic(
            @ForAll @LongRange(min = 1, max = 1_000_000) long startTime,
            @ForAll @IntRange(min = 2, max = 100) int tickCount) {

        AtomicLong wallClock = new AtomicLong(startTime);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        List<HlcTimestamp> timestamps = new ArrayList<>();
        for (int i = 0; i < tickCount; i++) {
            timestamps.add(hlc.tick());
            // Randomly advance wall clock (or not)
            if (i % 3 == 0) {
                wallClock.addAndGet(1);
            }
        }

        for (int i = 1; i < timestamps.size(); i++) {
            assertThat(timestamps.get(i))
                    .as("tick %d must be after tick %d", i, i - 1)
                    .isGreaterThan(timestamps.get(i - 1));
        }
    }

    // Property 2: update() preserves causality

    @Property(tries = 1000)
    void updatePreservesCausality(
            @ForAll @LongRange(min = 1, max = 1_000_000) long localTime,
            @ForAll @LongRange(min = 1, max = 1_000_000) long remotePhysical,
            @ForAll @IntRange(min = 0, max = 100) int remoteLogical) {

        AtomicLong wallClock = new AtomicLong(localTime);
        var hlc = new HybridLogicalClock("local", wallClock::get);

        HlcTimestamp received = new HlcTimestamp(
                remotePhysical, remoteLogical, "remote");

        HlcTimestamp result = hlc.update(received);

        // Result must be strictly after the received timestamp
        assertThat(result.isAfter(received))
                .as("update result must be after received: result=%s, received=%s",
                        result, received)
                .isTrue();
    }

    // Property 3: tick() never goes backward, even with backward wall clock

    @Property(tries = 1000)
    void tickNeverGoesBackwardEvenWithClockDrift(
            @ForAll @LongRange(min = 1000, max = 100_000) long startTime,
            @ForAll List<@LongRange(min = -5000, max = 5000) Long> drifts) {

        AtomicLong wallClock = new AtomicLong(startTime);
        var hlc = new HybridLogicalClock("drifty-node", wallClock::get);

        HlcTimestamp prev = hlc.tick();
        for (Long drift : drifts) {
            wallClock.addAndGet(drift); // may go backward
            if (wallClock.get() < 0) wallClock.set(0); // keep non-negative
            HlcTimestamp current = hlc.tick();

            assertThat(current.isAfter(prev))
                    .as("After drift %d, current=%s must be after prev=%s",
                            drift, current, prev)
                    .isTrue();
            prev = current;
        }
    }

    // Property 4: update then tick maintains ordering

    @Property(tries = 1000)
    void updateThenTickMaintainsOrdering(
            @ForAll @LongRange(min = 1, max = 1_000_000) long localTime,
            @ForAll @LongRange(min = 1, max = 1_000_000) long remotePhysical,
            @ForAll @IntRange(min = 0, max = 50) int remoteLogical,
            @ForAll @IntRange(min = 1, max = 10) int ticksAfterUpdate) {

        AtomicLong wallClock = new AtomicLong(localTime);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        HlcTimestamp received = new HlcTimestamp(
                remotePhysical, remoteLogical, "node-2");
        HlcTimestamp afterUpdate = hlc.update(received);

        HlcTimestamp prev = afterUpdate;
        for (int i = 0; i < ticksAfterUpdate; i++) {
            HlcTimestamp next = hlc.tick();
            assertThat(next.isAfter(prev))
                    .as("tick %d after update must be after previous", i)
                    .isTrue();
            prev = next;
        }
    }

    // Property 5: two-node causal chain is totally ordered

    @Property(tries = 500)
    void twoNodeCausalChainIsTotallyOrdered(
            @ForAll @LongRange(min = 1000, max = 50_000) long timeA,
            @ForAll @LongRange(min = 1000, max = 50_000) long timeB,
            @ForAll @IntRange(min = 2, max = 20) int exchanges) {

        AtomicLong clockA = new AtomicLong(timeA);
        AtomicLong clockB = new AtomicLong(timeB);
        var hlcA = new HybridLogicalClock("A", clockA::get);
        var hlcB = new HybridLogicalClock("B", clockB::get);

        List<HlcTimestamp> allTimestamps = new ArrayList<>();

        for (int i = 0; i < exchanges; i++) {
            if (i % 2 == 0) {
                // A sends to B
                HlcTimestamp send = hlcA.tick();
                allTimestamps.add(send);
                HlcTimestamp recv = hlcB.update(send);
                allTimestamps.add(recv);
            } else {
                // B sends to A
                HlcTimestamp send = hlcB.tick();
                allTimestamps.add(send);
                HlcTimestamp recv = hlcA.update(send);
                allTimestamps.add(recv);
            }
            // Advance clocks slightly
            clockA.addAndGet(1);
            clockB.addAndGet(1);
        }

        // Every consecutive pair in the causal chain must be ordered
        for (int i = 1; i < allTimestamps.size(); i++) {
            assertThat(allTimestamps.get(i))
                    .as("Step %d must be after step %d in causal chain", i, i - 1)
                    .isGreaterThan(allTimestamps.get(i - 1));
        }
    }
}
