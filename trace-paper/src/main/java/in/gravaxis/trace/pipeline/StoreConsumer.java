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

    private final long[] buffer = new long[DRAIN_LIMIT * EventRecords.LONGS];
    private final RecordBatch batch = new RecordBatch(DRAIN_LIMIT);
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();

    private final AtomicLong recordsStored = new AtomicLong();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong storeFailures = new AtomicLong();

    private volatile boolean running = true;
    private volatile long lastForceAt;
    private volatile long lastSealAt;

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
        this.capture = capture;
        this.journal = journal;
        this.store = store;
        this.logger = logger;
        this.forceIntervalMillis = forceIntervalMillis;
        this.sealIntervalMillis = sealIntervalMillis;
        this.lastForceAt = System.currentTimeMillis();
        this.lastSealAt = System.currentTimeMillis();
    }

    @Override
    public void run() {
        while (running) {
            try {
                int drained = drainRings();
                flushBuffer();
                maybeForce();
                maybeSeal();
                serveRequests();
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
        } catch (Exception e) {
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
            long lowWatermark = minCapture;
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
            logger.error("Could not write {} captured records; they stay in the ring for the next pass", buffered, e);
        } finally {
            buffered = 0;
            minCapture = Long.MAX_VALUE;
            maxCapture = Long.MIN_VALUE;
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
