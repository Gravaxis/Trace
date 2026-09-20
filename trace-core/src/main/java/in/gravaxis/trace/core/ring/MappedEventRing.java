/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.ring;

import in.gravaxis.trace.core.record.EventRecords;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A single-producer, single-consumer ring of 32-byte records, backed by a memory-mapped file.
 *
 * <p>One of these per producer thread, which together make the capture path lock-free without any
 * shared counter (ADR-0012). Mapped rather than heap-allocated for three reasons, in ascending
 * order of importance: a half-megabyte {@code long[]} is a humongous object on a small G1 region;
 * the collector never has to scan it; and its contents outlive the process, so a {@code kill -9}
 * costs nothing that was published here.
 *
 * <p>Publication is a release store of the tail after the four words are written, and the consumer
 * reads the tail with an acquire load, so a consumer can never see a half-written record. The head
 * advances only after the consumer has durably handed the records on.
 *
 * <p>The offer path allocates nothing and never blocks: if the ring is full it returns false and
 * the caller decides (spill, or count a drop and record a gap). A logger that stalls a tick thread
 * to avoid losing an event has the trade backwards.
 */
public final class MappedEventRing implements AutoCloseable {

    /** {@code TRRG} in ASCII. */
    private static final int MAGIC = 0x54525247;

    private static final int FORMAT_VERSION = 1;

    // Header layout. Head and tail sit a cache line apart so the two threads never share one.
    private static final long OFFSET_MAGIC = 0;
    private static final long OFFSET_VERSION = 4;
    private static final long OFFSET_SLOT = 8;
    private static final long OFFSET_CAPACITY = 12;
    private static final long OFFSET_GENERATION = 16;
    private static final long OFFSET_TAIL = 128;
    private static final long OFFSET_HEAD = 256;
    private static final long OFFSET_DROPPED = 384;
    private static final long DATA_OFFSET = 4096;

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;

    /**
     * Ordered access to the head and tail counters.
     *
     * <p>Exact-invoke: an inexact call goes through argument adaptation, which can allocate on a
     * path whose entire contract is that it does not.
     */
    private static final VarHandle CURSOR = ValueLayout.JAVA_LONG.varHandle().withInvokeExactBehavior();

    private final MemorySegment segment;
    private final Arena arena;
    private final int capacity;
    private final long mask;
    private final int slot;

    /** Producer-private copies, so the common path touches no shared memory. */
    private long tailCache;

    private long headCache;

    private MappedEventRing(MemorySegment segment, Arena arena, int capacity, int slot) {
        this.segment = segment;
        this.arena = arena;
        this.capacity = capacity;
        this.mask = capacity - 1L;
        this.slot = slot;
        this.tailCache = acquire(segment, OFFSET_TAIL);
        this.headCache = acquire(segment, OFFSET_HEAD);
    }

    /**
     * Opens (or creates) the ring file for a producer slot.
     *
     * @param capacityRecords records the ring holds; must be a power of two
     */
    public static MappedEventRing open(Path file, int slot, int capacityRecords) throws IOException {
        if (Integer.bitCount(capacityRecords) != 1) {
            throw new IllegalArgumentException("Ring capacity must be a power of two: " + capacityRecords);
        }
        Files.createDirectories(file.getParent());
        long bytes = DATA_OFFSET + (long) capacityRecords * EventRecords.BYTES;
        Arena arena = Arena.ofShared();
        try (FileChannel channel =
                FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MemorySegment segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes, arena);
            if (segment.get(INT, OFFSET_MAGIC) != MAGIC) {
                segment.fill((byte) 0);
                segment.set(INT, OFFSET_MAGIC, MAGIC);
                segment.set(INT, OFFSET_VERSION, FORMAT_VERSION);
                segment.set(INT, OFFSET_SLOT, slot);
                segment.set(INT, OFFSET_CAPACITY, capacityRecords);
            } else if (segment.get(INT, OFFSET_CAPACITY) != capacityRecords) {
                throw new IOException("Ring " + file + " was created with a different capacity: "
                        + segment.get(INT, OFFSET_CAPACITY));
            }
            return new MappedEventRing(segment, arena, capacityRecords, slot);
        } catch (RuntimeException | IOException e) {
            arena.close();
            throw e;
        }
    }

    public int slot() {
        return slot;
    }

    public int capacity() {
        return capacity;
    }

    /** Records published but not yet consumed. */
    public long pending() {
        return tail() - head();
    }

    /** Records the producer had to throw away because ring and spill were both full. */
    public long dropped() {
        return acquire(segment, OFFSET_DROPPED);
    }

    /**
     * Publishes one record. Producer thread only.
     *
     * <p>Allocation-free and wait-free: returns false rather than blocking, because a logger that
     * stalls the server to avoid losing an event has the trade backwards.
     *
     * @return true if the record was published, false if the ring is full
     */
    public boolean offer(long position, long states, long actorTime, long metadata) {
        long tail = tailCache;
        if (tail - headCache >= capacity) {
            headCache = head();
            if (tail - headCache >= capacity) {
                return false;
            }
        }
        long at = DATA_OFFSET + ((tail & mask) * EventRecords.BYTES);
        segment.set(LONG, at, position);
        segment.set(LONG, at + 8, states);
        segment.set(LONG, at + 16, actorTime);
        segment.set(LONG, at + 24, metadata);
        // Release: the four words above are visible to any consumer that sees this tail.
        tailCache = tail + 1;
        setRelease(OFFSET_TAIL, tail + 1);
        return true;
    }

    /** Counts a record the producer could not publish anywhere. */
    public void countDrop() {
        setRelease(OFFSET_DROPPED, dropped() + 1);
    }

    /**
     * Hands published records to {@code sink}, oldest first, without copying them anywhere else.
     *
     * <p>The head is advanced only after the sink returns, so a crash between reading and handing
     * on leaves the records in the ring for the next start to replay.
     *
     * @param limit most records to drain in this pass
     * @return how many were drained
     */
    public int drain(int limit, RecordSink sink) {
        long head = head();
        long tail = tailAcquire();
        int drained = 0;
        while (head < tail && drained < limit) {
            long at = DATA_OFFSET + ((head & mask) * EventRecords.BYTES);
            sink.accept(
                    segment.get(LONG, at),
                    segment.get(LONG, at + 8),
                    segment.get(LONG, at + 16),
                    segment.get(LONG, at + 24));
            head++;
            drained++;
        }
        if (drained > 0) {
            setRelease(OFFSET_HEAD, head);
        }
        return drained;
    }

    /**
     * Reads everything still unconsumed without advancing the head.
     *
     * <p>Used on restart: the ring file outlives the process, so whatever the consumer had not yet
     * journalled is still here.
     */
    public int replayPending(RecordSink sink) {
        long head = head();
        long tail = tailAcquire();
        int seen = 0;
        while (head + seen < tail) {
            long at = DATA_OFFSET + (((head + seen) & mask) * EventRecords.BYTES);
            sink.accept(
                    segment.get(LONG, at),
                    segment.get(LONG, at + 8),
                    segment.get(LONG, at + 16),
                    segment.get(LONG, at + 24));
            seen++;
        }
        return seen;
    }

    /** Marks everything currently published as consumed. */
    public void discardPending() {
        setRelease(OFFSET_HEAD, tailAcquire());
    }

    /** Starts the ring over, keeping its identity. Called after a recovered ring has been replayed. */
    public void reset() {
        segment.set(LONG, OFFSET_GENERATION, segment.get(LONG, OFFSET_GENERATION) + 1);
        setRelease(OFFSET_HEAD, 0);
        setRelease(OFFSET_TAIL, 0);
        tailCache = 0;
        headCache = 0;
    }

    /** Pushes the ring's pages to disk. Only needed at shutdown; the page cache survives a kill. */
    public void force() {
        segment.force();
    }

    @Override
    public void close() {
        arena.close();
    }

    private long head() {
        return acquire(segment, OFFSET_HEAD);
    }

    private long tail() {
        return acquire(segment, OFFSET_TAIL);
    }

    private long tailAcquire() {
        return acquire(segment, OFFSET_TAIL);
    }

    private void setRelease(long offset, long value) {
        CURSOR.setRelease(segment, offset, value);
    }

    private static long acquire(MemorySegment segment, long offset) {
        return (long) CURSOR.getAcquire(segment, offset);
    }

    /** Receives records as four longs, without any object in between. */
    @FunctionalInterface
    public interface RecordSink {
        void accept(long position, long states, long actorTime, long metadata);
    }
}
