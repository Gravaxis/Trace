/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.pipeline;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.ring.MappedEventRing;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MaintenancePolicy;
import in.gravaxis.trace.storage.MaintenanceSchedule;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.StoreException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;

/**
 * The single thread that moves records from the rings to disk.
 *
 * <p>Journal first, store second, always: the journal write is what bounds the cost of a crash, and
 * the store is told which journal position a batch came from so that replaying it after a restart
 * is a no-op rather than a duplicate.
 *
 * <p>A platform thread, not a virtual one. The work is a steady CPU-bound drain with blocking file
 * writes in the middle; a virtual thread would buy nothing here and add scheduling jitter, and the
 * JNI calls underneath SQLite pin their carrier anyway.
 */
public final class StoreConsumer implements Runnable {

    private static final int DRAIN_LIMIT = 4096;
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final CaptureService capture;
    private final JournalWriter journal;
    private final EventStore store;
    private final Logger logger;
    private final long forceIntervalMillis;
    private final long sealIntervalMillis;
    private final MaintenanceSchedule maintenance;

    private final long[] buffer = new long[DRAIN_LIMIT * EventRecords.LONGS];
    private final RecordBatch batch = new RecordBatch(DRAIN_LIMIT);
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();

    private final AtomicLong recordsStored = new AtomicLong();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong storeFailures = new AtomicLong();

    private volatile boolean running = true;
    private volatile long lastForceAt;
    private volatile long lastSealAt;

    // Consumer thread only: the drop window this pass would report, and what has been reported.
    private long lastDropCheckAt;
    private long reportedDrops;

    private int buffered;
    private long minCapture = Long.MAX_VALUE;
    private long maxCapture = Long.MIN_VALUE;

    public StoreConsumer(
            CaptureService capture,
            JournalWriter journal,
            EventStore store,
            Logger logger,
            long forceIntervalMillis,
            long sealIntervalMillis) {
        this(capture, journal, store, logger, forceIntervalMillis, sealIntervalMillis, MaintenancePolicy.defaults());
    }

    public StoreConsumer(
            CaptureService capture,
            JournalWriter journal,
            EventStore store,
            Logger logger,
            long forceIntervalMillis,
            long sealIntervalMillis,
            MaintenancePolicy policy) {
        this.capture = capture;
        this.journal = journal;
        this.store = store;
        this.logger = logger;
        this.forceIntervalMillis = forceIntervalMillis;
        this.sealIntervalMillis = sealIntervalMillis;
        this.lastForceAt = System.currentTimeMillis();
        this.lastSealAt = System.currentTimeMillis();
        this.lastDropCheckAt = System.currentTimeMillis();
        this.maintenance = new MaintenanceSchedule(store, policy, () -> !running, System.currentTimeMillis());
    }

    @Override
    public void run() {
        while (running) {
            try {
                int drained = drainRings();
                flushBuffer();
                maybeForce();
                maybeSeal();
                maybeRecordDrops();
                serveRequests();
                maintenance.tick(System.currentTimeMillis(), pendingRecords() > 0 || !requests.isEmpty());
                if (drained == 0) {
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                }
            } catch (Exception e) {
                storeFailures.incrementAndGet();
                // Never let one failure kill the consumer: that would silently stop all logging,
                // which is the failure mode this plugin exists to avoid.
                logger.error("Trace consumer hit an error; continuing", e);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            }
        }
    }

    /** Stops the loop after the current pass. */
    public void stop() {
        running = false;
    }

    public String maintenanceStatus() {
        return maintenance.status();
    }

    public long maintenanceCompleted() {
        return maintenance.completed();
    }

    public boolean maintenanceInProgress() {
        return maintenance.inProgress();
    }

    public long maintenanceElapsedNanos() {
        return maintenance.lastElapsedNanos();
    }

    public long maintenanceDeferred() {
        return maintenance.deferred();
    }

    /**
     * Drains everything, forces it, and seals it into immutable storage.
     *
     * <p>A rollback runs against sealed rows, so it asks for this first. Blocking: called from an
     * async thread, never from a tick thread.
     */
    public void flushAndSeal(long timeoutMillis) throws StoreException {
        Request request = new Request(true);
        requests.add(request);
        try {
            request.done.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof StoreException failure) throw failure;
            throw new StoreException(StoreException.Reason.INTERNAL, "Storage flush failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException(StoreException.Reason.CONTENDED, "Storage flush interrupted", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new StoreException(
                    StoreException.Reason.CONTENDED,
                    "The storage consumer did not finish flushing within " + timeoutMillis + "ms",
                    e);
        }
    }

    public long recordsStored() {
        return recordsStored.get();
    }

    public long framesWritten() {
        return framesWritten.get();
    }

    public long storeFailures() {
        return storeFailures.get();
    }

    public long forcedLsn() {
        return journal.forcedLsn();
    }

    private int drainRings() {
        int total = 0;
        for (MappedEventRing ring : capture.rings()) {
            total += ring.drain(DRAIN_LIMIT - buffered, this::buffer);
            if (buffered >= DRAIN_LIMIT) {
                flushBuffer();
            }
        }
        return total;
    }

    private void buffer(long position, long states, long actorTime, long metadata) {
        int base = buffered * EventRecords.LONGS;
        buffer[base] = position;
        buffer[base + 1] = states;
        buffer[base + 2] = actorTime;
        buffer[base + 3] = metadata;
        long capturedAt = TraceEpoch.toAbsolute(EventRecords.relativeMillis(actorTime));
        minCapture = Math.min(minCapture, capturedAt);
        maxCapture = Math.max(maxCapture, capturedAt);
        buffered++;
    }

    private void flushBuffer() {
        if (buffered == 0) {
            return;
        }
        try {
            // The low-water mark bounds a crash gap later: it is the oldest capture time that is
            // still only in memory as this frame is written.
            // The oldest capture that could still be missing after a crash: the oldest record in
            // this frame, or something older still staged on a capture thread that has not reached
            // its tick end. Taking only this frame's minimum would let a gap start after an event
            // staged earlier on another region thread, which is the one thing a gap must never do.
            long lowWatermark = Math.min(minCapture, capture.oldestStagedMillis());
            long lsn = journal.appendEvents(buffer, buffered, minCapture, maxCapture, lowWatermark);
            framesWritten.incrementAndGet();

            batch.clear();
            for (int i = 0; i < buffered; i++) {
                int base = i * EventRecords.LONGS;
                batch.add(buffer[base], buffer[base + 1], buffer[base + 2], buffer[base + 3]);
            }
            store.append(batch, lsn);
            recordsStored.addAndGet(buffered);
        } catch (IOException | StoreException e) {
            storeFailures.incrementAndGet();
            // These records are gone. The ring's head moved when they were drained into this
            // buffer, so there is nothing to retry from, and the buffer is cleared below. Saying
            // they "stay in the ring for the next pass", as this used to, was simply false. The
            // only honest thing left is to record the hole so that a rollback over it refuses.
            logger.error("Lost {} captured records that could not be written; recording a gap", buffered, e);
            recordGap(
                    minCapture,
                    maxCapture,
                    GapRecord.Reason.OVERFLOW,
                    buffered,
                    "a journal or store write failed: " + e.getMessage());
        } finally {
            buffered = 0;
            minCapture = Long.MAX_VALUE;
            maxCapture = Long.MIN_VALUE;
        }
    }

    /**
     * Turns records capture had to throw away into a gap.
     *
     * <p>ADR-0012 is explicit that a drop is only acceptable because it becomes a gap, and until
     * now it did not: the counter went up and nothing else happened, so a rollback over a window
     * where the ring had overflowed would have run believing the history complete.
     *
     * <p>The window is from the previous check to now. A record counted as dropped in this pass was
     * dropped after the previous pass, so that interval always contains it; the capture path does
     * not keep the timestamps of things it threw away, and inventing a narrower window would be
     * guessing in the one direction that matters.
     */
    private void maybeRecordDrops() {
        long now = System.currentTimeMillis();
        if (now - lastDropCheckAt < forceIntervalMillis) {
            return;
        }
        long dropped = capture.lossCount();
        long unreported = dropped - reportedDrops;
        if (unreported <= 0) {
            lastDropCheckAt = now;
            return;
        }
        boolean recorded = recordGap(
                capture.lossFromMillis(),
                Math.max(now, capture.lossToMillis()),
                GapRecord.Reason.OVERFLOW,
                unreported,
                unreported
                        + " records rejected by capture; conservative persisted loss window includes producer exhaustion, ring/clock overflow and out-of-range positions");
        if (recorded) {
            reportedDrops = dropped;
            lastDropCheckAt = now;
        }
        // If the gap could not be written, the window stays open and the next pass tries again with
        // a wider one. Losing the record of a loss is the one outcome worth retrying for.
    }

    private boolean recordGap(long fromMillis, long toMillis, GapRecord.Reason reason, long count, String detail) {
        long from = Math.min(fromMillis, toMillis);
        long to = Math.max(fromMillis, toMillis);
        try {
            store.recordGap(new GapRecord(from, to, reason, count, detail));
            return true;
        } catch (StoreException | RuntimeException e) {
            logger.error("Could not record a gap for {} lost records between {} and {}", count, from, to, e);
            return false;
        }
    }

    private void maybeForce() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastForceAt >= forceIntervalMillis) {
            journal.force();
            lastForceAt = now;
        }
    }

    private void maybeSeal() throws StoreException {
        long now = System.currentTimeMillis();
        if (now - lastSealAt >= sealIntervalMillis) {
            store.seal();
            lastSealAt = now;
        }
    }

    private void serveRequests() {
        Request request;
        while ((request = requests.poll()) != null) {
            try {
                // Drain until the rings are empty, not just one pass: the caller is about to read.
                while (drainRings() > 0) {
                    flushBuffer();
                }
                flushBuffer();
                journal.force();
                lastForceAt = System.currentTimeMillis();
                if (request.seal) {
                    store.seal();
                    lastSealAt = System.currentTimeMillis();
                }
                request.done.complete(null);
            } catch (Exception e) {
                request.done.completeExceptionally(e);
            }
        }
    }

    /** Rings whose records are still unconsumed, for reporting. */
    public long pendingRecords() {
        long pending = 0;
        List<MappedEventRing> rings = capture.rings();
        for (MappedEventRing ring : rings) {
            pending += ring.pending();
        }
        return pending;
    }

    private record Request(boolean seal, CompletableFuture<Void> done) {
        Request(boolean seal) {
            this(seal, new CompletableFuture<>());
        }
    }
}
