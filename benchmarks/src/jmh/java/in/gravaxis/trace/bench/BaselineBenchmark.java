/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import in.gravaxis.trace.core.record.EventRecords;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Establishes what "zero allocation" measures as on this machine, and proves the measurement can see
 * an allocation when there is one.
 *
 * <p>Gate P1 requires the capture path to allocate nothing, and {@link CaptureBenchmark} measures
 * that path. This benchmark answers the prior question the gate's threshold rests on: what does
 * JMH's GC profiler report for code that provably allocates nothing, and can it see an allocation
 * when there is one? {@link #emptyBaseline()} is the floor and {@link #allocatingReference()} is
 * the control, which must <em>not</em> read as zero — a green gate over a broken profiler would be
 * worse than no gate.
 *
 * <p>See docs/decisions/0009-allocation-gate.md for what the measurements came out as.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class BaselineBenchmark {

    /** Longs per record, so the control allocates exactly what one record would. */
    private static final int LONGS_PER_RECORD = EventRecords.LONGS;

    private int counter;

    @Setup
    public void setUp() {
        counter = 0;
    }

    /** The measurement floor: no work, no allocation. */
    @Benchmark
    public int emptyBaseline() {
        return counter++;
    }

    /**
     * The control. If the GC profiler ever reports this as zero, the gate is measuring nothing and
     * the other results mean nothing either.
     */
    @Benchmark
    public long[] allocatingReference() {
        long[] record = new long[LONGS_PER_RECORD];
        record[0] = counter++;
        return record;
    }
}
