/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.ring.MappedEventRing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives the real capture path for both instruments of gate P1.
 *
 * <p>There is deliberately no encoding logic here. This class only calls
 * {@link CaptureService#captureBlockChange} and {@link CaptureService#confirmStaged}, which is
 * exactly what {@code BlockCaptureListener} calls on a tick thread, so the bytecode the gate
 * measures is the bytecode the server runs. The predecessor of this file was a hand-written probe
 * shaped like the encoder, and a gate over a stand-in cannot fail when the real thing regresses.
 *
 * <p>Two paths matter and they are measured separately, because the class has no third one: a tick
 * whose events all turn out to have changed nothing (staged, then rejected at tick end — the
 * ADR-0014 path), and a tick whose events all really happened (staged, then stamped, packed and
 * written into the ring). There is no way to stage without then either confirming or flushing, so
 * "staging alone" is not a measurable path and is not pretended to be one.
 *
 * <p><strong>What is not measured.</strong> Bukkit's event dispatch, the dictionary lookups that
 * turn a Material and a UUID into ids, and the real world read inside the tick-end handler all live
 * in {@code trace-paper} and need a server. Gate P1 covers the encoder, not the listener around it.
 */
public final class CaptureHarness implements AutoCloseable {

    /** Block changes in one measured tick. A busy tick; comfortably inside the staging buffer. */
    public static final int RECORDS_PER_TICK = 256;

    /**
     * Ring capacity, far above one tick so a measured tick never meets a full ring.
     *
     * <p>A full ring sends every record down the drop path, which allocates nothing either — so the
     * benchmark would stay green while measuring the wrong branch entirely.
     */
    private static final int RING_CAPACITY = 1 << 16;

    /**
     * The clock drift bound the harness runs with, far above production's two milliseconds.
     *
     * <p>{@link CaptureService} refuses to stamp a record whose slot clock would run further ahead
     * of wall time than the bound allows, and a benchmark publishes far faster than any server: at
     * the production bound almost every record would take the drop branch, and the benchmark would
     * measure that instead of publishing. Raising the bound makes the measurement traverse the path
     * a real server traverses. Nothing else about the path changes.
     */
    private static final long BENCH_CLOCK_DRIFT_MILLIS = 1_000_000L;

    private static final int WORLD = 1;
    private static final int ACTOR = 7;
    private static final int CAUSE = 1;
    private static final int KIND = 1;
    private static final int BEFORE_STATE = 17;
    private static final int Y = -54;

    /** Readers held as fields, so calling one cannot allocate a capturing lambda per tick. */
    private final CaptureService.StateReader unchanged = (world, x, y, z) -> BEFORE_STATE;

    private final CaptureService.StateReader changed = (world, x, y, z) -> BEFORE_STATE + 1;
    private final CaptureService.StateReader unavailable = (world, x, y, z) -> CaptureService.UNKNOWN_STATE;
    private final CaptureService.StateReader invalid = (world, x, y, z) -> EventRecords.MAX_STATE_ID + 1;

    private final Path directory;
    private final boolean ownsDirectory;
    private final CaptureService service;
    private MappedEventRing ring;
    private int counter;

    private CaptureHarness(Path directory, boolean ownsDirectory) {
        this.directory = directory;
        this.ownsDirectory = ownsDirectory;
        this.service = new CaptureService(directory, RING_CAPACITY, BENCH_CLOCK_DRIFT_MILLIS);
    }

    /** A harness in a directory the caller owns, such as a test's temporary directory. */
    public static CaptureHarness in(Path directory) {
        CaptureHarness harness = new CaptureHarness(directory, false);
        harness.prime();
        return harness;
    }

    /** A harness in a temporary directory it deletes on close. */
    public static CaptureHarness inTemporaryDirectory() {
        Path directory;
        try {
            directory = Files.createTempDirectory("trace-capture-bench");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create a directory for the capture rings", e);
        }
        CaptureHarness harness = new CaptureHarness(directory, true);
        harness.prime();
        return harness;
    }

    /**
     * Opens the ring and takes the reference the measured code will use.
     *
     * <p>{@link CaptureService#rings()} copies its list, so it must never be called from inside a
     * measured tick.
     */
    private void prime() {
        tickRejecting();
        tickPublishing();
        List<MappedEventRing> rings = service.rings();
        if (rings.size() != 1) {
            throw new IllegalStateException("Expected exactly one producer ring, found " + rings.size());
        }
        ring = rings.getFirst();
        ring.discardPending();
    }

    /** One tick whose events all turn out to have changed nothing. Publishes no records. */
    public void tickRejecting() {
        stage(RECORDS_PER_TICK);
        service.confirmStaged(unchanged);
    }

    /**
     * One tick whose events all really happened: staged, stamped, packed, and written to the ring.
     *
     * <p>The drain at the end is the consumer's {@code discardPending}, one release store. It is
     * here because without it the ring fills and the measurement silently becomes a drop-path
     * measurement; it is hot-path code itself, so including it costs the measurement nothing it
     * should not already cover.
     */
    public void tickPublishing() {
        stage(RECORDS_PER_TICK);
        service.confirmStaged(changed);
        if (ring != null) {
            ring.discardPending();
        }
    }

    public void tickCoalescing() {
        stageRepeated(false);
        service.confirmStaged(changed);
        ring.discardPending();
    }

    public void tickAmbiguous() {
        stageRepeated(true);
        service.confirmStaged(changed);
    }

    public void tickUnavailable() {
        stage(RECORDS_PER_TICK);
        service.confirmStaged(unavailable);
    }

    public void tickInvalidState() {
        stage(RECORDS_PER_TICK);
        service.confirmStaged(invalid);
    }

    public void tickUnconfirmedFlush() {
        stage(RECORDS_PER_TICK);
        service.flushStagedUnconfirmed();
    }

    public void tickStagingFull() {
        stage(CaptureService.STAGING_CAPACITY + 1);
        service.confirmStaged(unchanged);
    }

    private void stageRepeated(boolean mixedActor) {
        for (int i = 0; i < RECORDS_PER_TICK; i++) {
            service.captureBlockChange(WORLD, i, Y, 0, BEFORE_STATE, ACTOR, CAUSE, KIND);
            service.captureBlockChange(WORLD, i, Y, 0, BEFORE_STATE, ACTOR + (mixedActor ? 1 : 0), CAUSE, KIND);
        }
    }

    public CaptureService service() {
        return service;
    }

    private void stage(int records) {
        for (int i = 0; i < records; i++) {
            int next = counter++;
            // Varying, and inside the record's addressable range: a position out of range is
            // counted and dropped before it reaches the encoder, which would make a zero here mean
            // nothing. The test asserts outOfRange() stayed at zero.
            int x = next & 0xFFFF;
            int z = (next >>> 16) & 0xFFFF;
            service.captureBlockChange(WORLD, x, Y, z, BEFORE_STATE, ACTOR, CAUSE, KIND);
        }
    }

    /** True when every coordinate this harness produced was inside the encoder's range. */
    public static boolean coordinatesAreInRange() {
        return EventRecords.positionInRange(0, Y, 0) && EventRecords.positionInRange(0xFFFF, Y, 0xFFFF);
    }

    public long captured() {
        return service.captured();
    }

    public long published() {
        return service.published();
    }

    public long rejectedUnchanged() {
        return service.rejectedUnchanged();
    }

    public long unconfirmed() {
        return service.unconfirmed();
    }

    public long dropped() {
        return service.dropped();
    }

    public long outOfRange() {
        return service.outOfRange();
    }

    @Override
    public void close() {
        service.close();
        if (!ownsDirectory) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // A leftover file in the system temporary directory is not worth failing a
                    // benchmark run over.
                }
            });
        } catch (IOException e) {
            // As above.
        }
    }
}
