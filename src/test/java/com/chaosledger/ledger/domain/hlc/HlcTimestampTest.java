package com.chaosledger.ledger.domain.hlc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HlcTimestampTest {

    @Test
    void shouldOrderByPhysicalTimeFirst() {
        var ts1 = new HlcTimestamp(100, 5, "node-1");
        var ts2 = new HlcTimestamp(200, 0, "node-1");
        assertThat(ts1).isLessThan(ts2);
    }

    @Test
    void shouldOrderByLogicalCounterWhenPhysicalTimeEqual() {
        var ts1 = new HlcTimestamp(100, 3, "node-1");
        var ts2 = new HlcTimestamp(100, 7, "node-1");
        assertThat(ts1).isLessThan(ts2);
    }

    @Test
    void shouldOrderByNodeIdAsTiebreaker() {
        var ts1 = new HlcTimestamp(100, 3, "node-1");
        var ts2 = new HlcTimestamp(100, 3, "node-2");
        assertThat(ts1).isLessThan(ts2);
    }

    @Test
    void shouldBeEqualWhenAllFieldsMatch() {
        var ts1 = new HlcTimestamp(100, 3, "node-1");
        var ts2 = new HlcTimestamp(100, 3, "node-1");
        assertThat(ts1).isEqualTo(ts2);
        assertThat(ts1.compareTo(ts2)).isZero();
    }

    @Test
    void isBeforeAndIsAfterShouldWork() {
        var earlier = new HlcTimestamp(100, 0, "node-1");
        var later = new HlcTimestamp(200, 0, "node-1");
        assertThat(earlier.isBefore(later)).isTrue();
        assertThat(later.isAfter(earlier)).isTrue();
        assertThat(earlier.isAfter(later)).isFalse();
    }

    @Test
    void shouldRejectNegativePhysicalTime() {
        assertThatThrownBy(() -> new HlcTimestamp(-1, 0, "node-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeLogicalCounter() {
        assertThatThrownBy(() -> new HlcTimestamp(100, -1, "node-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullNodeId() {
        assertThatThrownBy(() -> new HlcTimestamp(100, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectBlankNodeId() {
        assertThatThrownBy(() -> new HlcTimestamp(100, 0, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroShouldCreateInitialTimestamp() {
        var ts = HlcTimestamp.zero("node-1");
        assertThat(ts.physicalTime()).isZero();
        assertThat(ts.logicalCounter()).isZero();
        assertThat(ts.nodeId()).isEqualTo("node-1");
    }

    @Test
    void toStringShouldBeReadable() {
        var ts = new HlcTimestamp(1000, 5, "node-1");
        assertThat(ts.toString()).isEqualTo("1000:5@node-1");
    }
}