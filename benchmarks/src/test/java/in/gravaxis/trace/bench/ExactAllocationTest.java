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
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exact half of gate P1: the capture path must allocate <em>zero bytes</em>, counted rather
 * than sampled, on the real {@code CaptureService}.
 *
 * <p>JMH's GC profiler normalises a whole iteration's allocation over its operations, so its floor
 * is small but not exactly zero (see docs/decisions/0009-allocation-gate.md). The JVM's per-thread
 * allocation counter has no such floor: it reports whole bytes, so "zero" means zero. It also does
 * not divide by the number of records in a tick, which is why this is the gate of record.
 *
 * <p>The build runs this twice — once normally and once with escape analysis and C2 disabled — so a
 * pass cannot depend on the optimiser scalar-replacing an allocation that would be real in the
 * interpreter, which is where a cold server actually starts.
 *
 * <p>Every test asserts on the counters as well as the bytes. A measurement of zero means nothing
 * unless the code took the branch the test claims it took: a position rejected as out of range, or
 * a record dropped into a full ring, also allocates nothing.
 */
class ExactAllocationTest {

    private static final int WARMUP_TICKS = 200;
    private static final int MEASURED_TICKS = 800;
    private static final int MEASUREMENT_WINDOWS = 5;

    @TempDir
    Path directory;

    private ThreadMXBean threads;
    private CaptureHarness harness;

    @BeforeEach
    void setUp() {
        threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threads.isThreadAllocatedMemorySupported())
                .as("this JVM must be able to count per-thread allocation for the gate to mean anything")
                .isTrue();
        threads.setThreadAllocatedMemoryEnabled(true);
        assertThat(CaptureHarness.coordinatesAreInRange())
                .as("the harness must generate positions the encoder accepts")
                .isTrue();
        harness = CaptureHarness.in(directory);
    }

    @AfterEach
    void tearDown() {
        // Releases the mapped rings. Windows will not let @TempDir delete a mapped file.
        harness.close();
    }

    @Test
    @DisplayName("a tick of events that changed nothing allocates nothing at all")
    void rejectingATickAllocatesNothing() {
        long rejectedBefore = harness.rejectedUnchanged();
        long publishedBefore = harness.published();

        long cleanest = quietestWindow(harness::tickRejecting);

        assertThat(harness.rejectedUnchanged() - rejectedBefore)
                .as("every staged record must have been rejected, or this measured the wrong path")
                .isEqualTo(
                        (long) (WARMUP_TICKS + MEASURED_TICKS * MEASUREMENT_WINDOWS) * CaptureHarness.RECORDS_PER_TICK);
        assertThat(harness.published() - publishedBefore)
                .as("the rejection path must publish nothing")
                .isZero();
        assertZeroBytes(cleanest, "rejecting");
    }

    @Test
    @DisplayName("a tick of real changes, packed and written to the ring, allocates nothing at all")
    void publishingATickAllocatesNothing() {
        long publishedBefore = harness.published();
        long droppedBefore = harness.dropped();

        long cleanest = quietestWindow(harness::tickPublishing);

        assertThat(harness.published() - publishedBefore)
                .as("every staged record must have reached the ring, or this measured the drop path")
                .isEqualTo(
                        (long) (WARMUP_TICKS + MEASURED_TICKS * MEASUREMENT_WINDOWS) * CaptureHarness.RECORDS_PER_TICK);
        assertThat(harness.dropped() - droppedBefore)
                .as("a dropped record allocates nothing either, so a drop would make this test lie")
                .isZero();
        assertThat(harness.outOfRange())
                .as("a position outside the encoder's range never reaches the encoder")
                .isZero();
        assertThat(harness.unconfirmed())
                .as("nothing should have been published without confirmation")
                .isZero();
        assertZeroBytes(cleanest, "publishing");
    }

    @Test
    void coalescedPublicationAllocatesNothingAndPublishesOneRowPerPosition() {
        long before = harness.published();
        long cleanest = quietestWindow(harness::tickCoalescing);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().coalesced()).isEqualTo(expected);
        assertThat(harness.published() - before).isEqualTo(expected);
        assertThat(harness.dropped()).isZero();
        assertZeroBytes(cleanest, "coalesced-publication");
    }

    @Test
    void ambiguousObservationAllocatesNothingAndGapsBothInputs() {
        long before = harness.published();
        long cleanest = quietestWindow(harness::tickAmbiguous);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().coalesced()).isEqualTo(expected);
        assertThat(harness.service().droppedAmbiguous()).isEqualTo(expected * 2);
        assertThat(harness.dropped()).isEqualTo(expected * 2);
        assertThat(harness.service().lossCount()).isEqualTo(expected * 2);
        assertThat(harness.published()).isEqualTo(before);
        assertZeroBytes(cleanest, "ambiguous-loss");
    }

    @Test
    void missingConfirmationAllocatesNothingAndRecordsLoss() {
        long before = harness.published();
        long cleanest = quietestWindow(harness::tickUnavailable);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().droppedUnreadable()).isEqualTo(expected);
        assertThat(harness.unconfirmed()).isEqualTo(expected);
        assertThat(harness.dropped()).isEqualTo(expected);
        assertThat(harness.service().lossCount()).isEqualTo(expected);
        assertThat(harness.published()).isEqualTo(before);
        assertZeroBytes(cleanest, "unavailable-loss");
    }

    @Test
    void invalidPostStateAllocatesNothingAndCannotBePacked() {
        long before = harness.published();
        long cleanest = quietestWindow(harness::tickInvalidState);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().invalidFields()).isEqualTo(expected);
        assertThat(harness.dropped()).isEqualTo(expected);
        assertThat(harness.service().lossCount()).isEqualTo(expected);
        assertThat(harness.published()).isEqualTo(before);
        assertZeroBytes(cleanest, "invalid-state-loss");
    }

    @Test
    void unconfirmedFlushAllocatesNothingAndOnlyGaps() {
        long before = harness.published();
        long cleanest = quietestWindow(harness::tickUnconfirmedFlush);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().droppedUnconfirmedFlush()).isEqualTo(expected);
        assertThat(harness.unconfirmed()).isEqualTo(expected);
        assertThat(harness.dropped()).isEqualTo(expected);
        assertThat(harness.service().lossCount()).isEqualTo(expected);
        assertThat(harness.published()).isEqualTo(before);
        assertZeroBytes(cleanest, "unconfirmed-flush-loss");
    }

    @Test
    void stagingExhaustionAllocatesNothingAndDoesNotPublish() {
        long before = harness.published();
        long rejected = harness.rejectedUnchanged();
        long cleanest = quietestWindow(harness::tickStagingFull);
        assertThat(harness.service().droppedStagingFull()).isEqualTo(measuredTicks());
        assertThat(harness.unconfirmed()).isEqualTo(measuredTicks());
        assertThat(harness.dropped()).isEqualTo(measuredTicks());
        assertThat(harness.service().lossCount()).isEqualTo(measuredTicks());
        assertThat(harness.rejectedUnchanged() - rejected)
                .isEqualTo(measuredTicks() * in.gravaxis.trace.core.capture.CaptureService.STAGING_CAPACITY);
        assertThat(harness.published()).isEqualTo(before);
        assertZeroBytes(cleanest, "staging-full-loss");
    }

    @Test
    void missingDictionaryDependencyAllocatesNothingAndOnlyGaps() {
        long published = harness.published();
        long cleanest = quietestWindow(harness::tickMissingDependency);
        long expected = measuredTicks() * CaptureHarness.RECORDS_PER_TICK;
        assertThat(harness.service().droppedDependency()).isEqualTo(expected);
        assertThat(harness.service().lossCount()).isEqualTo(expected);
        assertThat(harness.dropped()).isEqualTo(expected);
        assertThat(harness.published()).isEqualTo(published);
        assertZeroBytes(cleanest, "missing-dictionary-loss");
    }

    private static long measuredTicks() {
        return WARMUP_TICKS + (long) MEASURED_TICKS * MEASUREMENT_WINDOWS;
    }

    @Test
    @DisplayName("the counter itself can see an allocation, so a zero above means something")
    void counterDetectsAllocation() {
        long before = threads.getCurrentThreadAllocatedBytes();
        long sink = 0;
        for (int i = 0; i < 10_000; i++) {
            long[] record = new long[4];
            record[0] = i;
            sink += record[0];
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(sink).isNotNegative();
        assertThat(allocated)
                .as("allocating ten thousand small arrays must register")
                .isGreaterThan(10_000L);
    }

    /**
     * Runs {@code tick} for a warm-up and then several measured windows, returning the quietest.
     *
     * <p>Several windows, not one. A per-operation allocation shows up in every window; the odd JVM
     * housekeeping allocation — a class loaded, a compilation side effect — shows up in one.
     * Requiring one clean window is therefore strict about the property and immune to the noise.
     */
    private long quietestWindow(Runnable tick) {
        for (int i = 0; i < WARMUP_TICKS; i++) {
            tick.run();
        }
        long[] windows = new long[MEASUREMENT_WINDOWS];
        for (int window = 0; window < MEASUREMENT_WINDOWS; window++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MEASURED_TICKS; i++) {
                tick.run();
            }
            windows[window] = threads.getCurrentThreadAllocatedBytes() - before;
        }
        lastWindows = windows;
        return Arrays.stream(windows).min().orElseThrow();
    }

    private long[] lastWindows = new long[0];

    private void assertZeroBytes(long cleanest, String path) {
        // Outside the measured windows: retain the actual samples in the committed test report.
        System.out.printf("path=%s windowBytes=%s quietestBytes=%d%n", path, Arrays.toString(lastWindows), cleanest);
        assertThat(cleanest)
                .as(
                        "%s: quietest of %d windows of %,d ticks of %d records (all windows: %s)",
                        path,
                        MEASUREMENT_WINDOWS,
                        MEASURED_TICKS,
                        CaptureHarness.RECORDS_PER_TICK,
                        Arrays.toString(lastWindows))
                .isZero();
    }
}
