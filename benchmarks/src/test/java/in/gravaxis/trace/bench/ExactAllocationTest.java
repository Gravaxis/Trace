/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exact half of gate P1: the encode path must allocate <em>zero bytes</em>, counted rather than
 * sampled.
 *
 * <p>JMH's GC profiler normalises a whole iteration's allocation over its operations, so its floor
 * is small but not exactly zero (see docs/decisions/0009-allocation-gate.md). The JVM's per-thread
 * allocation counter has no such floor: it reports whole bytes, so "zero" means zero.
 *
 * <p>The build runs this twice — once normally and once with escape analysis and C2 disabled — so a
 * pass cannot depend on the optimiser scalar-replacing an allocation that would be real in the
 * interpreter, which is where a cold server actually starts.
 */
class ExactAllocationTest {

    private static final int WARMUP_OPERATIONS = 50_000;
    private static final int MEASURED_OPERATIONS = 200_000;
    private static final int MEASUREMENT_WINDOWS = 5;
    private static final int CAPACITY = 1 << 12;

    private ThreadMXBean threads;
    private long[] buffer;

    @BeforeEach
    void setUp() {
        threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threads.isThreadAllocatedMemorySupported())
                .as("this JVM must be able to count per-thread allocation for the gate to mean anything")
                .isTrue();
        threads.setThreadAllocatedMemoryEnabled(true);
        buffer = new long[CAPACITY * AllocationProbe.LONGS_PER_RECORD];
    }

    @Test
    @DisplayName("encoding a record into a preallocated ring allocates nothing at all")
    void encodeAllocatesNothing() {
        int cursor = 0;
        for (int i = 0; i < WARMUP_OPERATIONS; i++) {
            cursor = AllocationProbe.encodeInto(buffer, cursor, i);
        }

        // Several windows, not one. A per-operation allocation shows up in every window; the odd
        // JVM housekeeping allocation (a class loaded, a compilation side effect) shows up in one.
        // Requiring a clean window is therefore strict about the property and immune to the noise.
        long[] windows = new long[MEASUREMENT_WINDOWS];
        for (int window = 0; window < MEASUREMENT_WINDOWS; window++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED_OPERATIONS; i++) {
                cursor = AllocationProbe.encodeInto(buffer, cursor, i);
            }
            windows[window] = threads.getCurrentThreadAllocatedBytes() - before;
        }

        long cleanest = Arrays.stream(windows).min().orElseThrow();
        assertThat(cursor).isGreaterThanOrEqualTo(0);
        assertThat(cleanest)
                .as(
                        "quietest of %d windows of %,d encodes each (all windows: %s)",
                        MEASUREMENT_WINDOWS, MEASURED_OPERATIONS, Arrays.toString(windows))
                .isZero();
    }

    @Test
    @DisplayName("the counter itself can see an allocation, so a zero above means something")
    void counterDetectsAllocation() {
        long before = threads.getCurrentThreadAllocatedBytes();
        long sink = 0;
        for (int i = 0; i < 10_000; i++) {
            long[] record = new long[AllocationProbe.LONGS_PER_RECORD];
            record[0] = i;
            sink += record[0];
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(sink).isNotNegative();
        assertThat(allocated)
                .as("allocating ten thousand small arrays must register")
                .isGreaterThan(10_000L);
    }
}
