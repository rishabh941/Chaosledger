package com.chaosledger.ledger.domain.hlc;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A wall-clock supplier that can be skewed at runtime by a fixed offset.
 *
 * Used by Scenario 3.1 (clock-drift-forward-2s) to simulate a node whose
 * system clock has drifted, WITHOUT touching the container's actual OS
 * clock (which would affect the host, since containers share the host's
 * kernel clock unless an explicit time namespace is used).
 *
 * offsetMillis is applied additively: effective time = real time + offset.
 * A positive offset simulates a clock running fast (drifted forward).
 */
public class AdjustableClock implements LongSupplier {

    private final AtomicLong offsetMillis = new AtomicLong(0);

    @Override
    public long getAsLong() {
        return System.currentTimeMillis() + offsetMillis.get();
    }

    public void setOffsetMillis(long offset) {
        offsetMillis.set(offset);
    }

    public long getOffsetMillis() {
        return offsetMillis.get();
    }
}