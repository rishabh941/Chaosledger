package com.chaosledger.ledger.domain.hlc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class HybridLogicalClockTest {

    @Test
    void tickShouldReturnMonotonicallyIncreasingTimestamps() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        HlcTimestamp ts1 = hlc.tick();
        HlcTimestamp ts2 = hlc.tick();
        HlcTimestamp ts3 = hlc.tick();

        assertThat(ts1.isBefore(ts2)).isTrue();
        assertThat(ts2.isBefore(ts3)).isTrue();
    }

    @Test
    void tickShouldIncrementLogicalCounterWhenWallClockStagnant() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        HlcTimestamp ts1 = hlc.tick();
        HlcTimestamp ts2 = hlc.tick();
        HlcTimestamp ts3 = hlc.tick();

        // Wall clock didn't move, so logical counter increments
        assertThat(ts1.physicalTime()).isEqualTo(1000);
        assertThat(ts1.logicalCounter()).isZero();
        assertThat(ts2.logicalCounter()).isEqualTo(1);
        assertThat(ts3.logicalCounter()).isEqualTo(2);
    }

    @Test
    void tickShouldResetLogicalCounterWhenWallClockAdvances() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        hlc.tick(); // physicalTime=1000, logical=0
        hlc.tick(); // physicalTime=1000, logical=1

        wallClock.set(2000); // wall clock advances
        HlcTimestamp ts3 = hlc.tick();

        assertThat(ts3.physicalTime()).isEqualTo(2000);
        assertThat(ts3.logicalCounter()).isZero(); // reset!
    }

    @Test
    void tickShouldNeverGoBackwardEvenIfWallClockGoesBackward() {
        AtomicLong wallClock = new AtomicLong(5000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        HlcTimestamp ts1 = hlc.tick();
        assertThat(ts1.physicalTime()).isEqualTo(5000);

        // Simulate clock going backward (NTP correction, manual set)
        wallClock.set(3000);
        HlcTimestamp ts2 = hlc.tick();

        // HLC should NOT go backward
        assertThat(ts2.physicalTime()).isEqualTo(5000);
        assertThat(ts2.logicalCounter()).isEqualTo(1);
        assertThat(ts2.isAfter(ts1)).isTrue();
    }

    @Test
    void updateShouldAdvanceBeyondReceivedTimestamp() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        // Received timestamp from a node with a faster clock
        HlcTimestamp received = new HlcTimestamp(5000, 3, "node-2");
        HlcTimestamp result = hlc.update(received);

        // Must be strictly after the received timestamp
        assertThat(result.isAfter(received)).isTrue();
    }

    @Test
    void updateShouldPreserveCausality() {
        AtomicLong clock1 = new AtomicLong(1000);
        AtomicLong clock2 = new AtomicLong(1000);
        var hlcA = new HybridLogicalClock("node-A", clock1::get);
        var hlcB = new HybridLogicalClock("node-B", clock2::get);

        // A sends a message
        HlcTimestamp sendTs = hlcA.tick();

        // B receives it — B's resulting timestamp must be after sendTs
        HlcTimestamp recvTs = hlcB.update(sendTs);
        assertThat(recvTs.isAfter(sendTs)).isTrue();

        // B does more work
        HlcTimestamp afterRecv = hlcB.tick();
        assertThat(afterRecv.isAfter(recvTs)).isTrue();
    }

    @Test
    void updateShouldHandleReceivedTimestampFromTheFuture() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        // Received from a node whose clock is far ahead
        HlcTimestamp futureTs = new HlcTimestamp(99000, 0, "node-2");
        HlcTimestamp result = hlc.update(futureTs);

        assertThat(result.physicalTime()).isEqualTo(99000);
        assertThat(result.isAfter(futureTs)).isTrue();

        // Subsequent ticks should stay >= the future timestamp
        HlcTimestamp next = hlc.tick();
        assertThat(next.isAfter(result)).isTrue();
    }

    @Test
    void updateShouldHandleSamePhysicalTimeAndSameLogical() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        hlc.tick(); // 1000:0
        HlcTimestamp received = new HlcTimestamp(1000, 0, "node-2");
        HlcTimestamp result = hlc.update(received);

        // Both local and received had physicalTime=1000.
        // Local had logical=0 (from tick), received had logical=0.
        // Result: max(0,0)+1 = 1
        assertThat(result.physicalTime()).isEqualTo(1000);
        assertThat(result.logicalCounter()).isGreaterThan(0);
    }

    @Test
    void currentShouldReturnStateWithoutAdvancing() {
        AtomicLong wallClock = new AtomicLong(1000);
        var hlc = new HybridLogicalClock("node-1", wallClock::get);

        hlc.tick();
        HlcTimestamp c1 = hlc.current();
        HlcTimestamp c2 = hlc.current();

        assertThat(c1).isEqualTo(c2); // no advancement
    }

    @Test
    void shouldIncludeCorrectNodeId() {
        var hlc = new HybridLogicalClock("my-node");
        HlcTimestamp ts = hlc.tick();
        assertThat(ts.nodeId()).isEqualTo("my-node");
    }
}