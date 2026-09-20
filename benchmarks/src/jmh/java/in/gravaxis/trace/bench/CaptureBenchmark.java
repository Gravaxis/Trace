/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Gate P1 over the real capture path: a tick's worth of block changes must allocate nothing.
 *
 * <p>Both benchmarks here drive {@link CaptureHarness}, which calls the same
 * {@code CaptureService} methods the server's listener calls. One measures a tick whose events
 * changed nothing and are rejected at tick end; the other measures a tick that is staged, stamped,
 * packed and written to the ring.
 *
 * <p>Results are reported per block change, not per tick, so the number is comparable with the
 * floor and the control in {@link BaselineBenchmark}. That division is also why the exact
 * per-thread byte count in {@code ExactAllocationTest} is the gate of record: a single small
 * allocation once per tick would divide down here, and there it cannot hide.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@OperationsPerInvocation(CaptureHarness.RECORDS_PER_TICK)
public class CaptureBenchmark {

    private CaptureHarness harness;

    @Setup(Level.Trial)
    public void setUp() {
        harness = CaptureHarness.inTemporaryDirectory();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        // The mapped rings have to be released, and on Windows the files cannot be deleted until
        // they are.
        harness.close();
    }

    /** A tick of events that turned out to have changed nothing: the ADR-0014 rejection path. */
    @Benchmark
    public long captureRejectedAtTickEnd() {
        harness.tickRejecting();
        return harness.rejectedUnchanged();
    }

    /** A tick of real changes: staged, stamped, packed into four longs, written to the ring. */
    @Benchmark
    public long capturePublishedToRing() {
        harness.tickPublishing();
        return harness.published();
    }
}
