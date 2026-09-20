/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.ring.MappedEventRing;
import in.gravaxis.trace.core.time.SlotClock;
import in.gravaxis.trace.core.time.TraceEpoch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns events into records, and decides which of them are real.
 *
 * <p>Two things happen here, and the second is the one worth reading about.
 *
 * <p>First, capture stages a record in a preallocated per-thread buffer. Nothing is allocated and
 * nothing is published yet.
 *
 * <p>Second — this is ADR-0014 — the record is published only if the world actually changed. At the
 * end of the tick, on the thread that owns the region, Trace reads the block again: if it still
 * looks exactly as it did before the event, nothing happened and the record is dropped. A client
 * mod punching a lava block fires break events endlessly; a logger that records events rather than
 * changes writes all of them, and inspecting the area afterwards shows a history that never
 * occurred. Confirming also means the stored after-state is what the world actually became rather
 * than what the event implied.
 *
 * <p>No Bukkit types appear here: the world is reached through {@link StateReader}, so the whole
 * capture path can be tested without a server.
 */
public final class CaptureService implements AutoCloseable {

    /** Producer slots; beyond this a thread shares the last one. */
    public static final int MAX_SLOTS = 32;

    /** Records a thread can stage within one tick before the rest are published unconfirmed. */
    public static final int STAGING_CAPACITY = 4096;

    /** How far the slot clock may run ahead of wall time before a record is dropped. */
    public static final long DEFAULT_MAX_CLOCK_DRIFT_MILLIS = 2;

    /** Returned by a {@link StateReader} when the position cannot be read right now. */
    public static final int UNKNOWN_STATE = -1;

    private final Path ringDirectory;
    private final int ringCapacity;
    private final long maxClockDriftMillis;
    private final AtomicInteger nextSlot = new AtomicInteger();
    private final List<MappedEventRing> rings = new CopyOnWriteArrayList<>();

    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong rejectedUnchanged = new AtomicLong();
    private final AtomicLong unconfirmed = new AtomicLong();
    private final AtomicLong droppedSlotOverflow = new AtomicLong();
    private final AtomicLong droppedRingFull = new AtomicLong();
    private final AtomicLong outOfRange = new AtomicLong();
    private final AtomicLong slotsExhausted = new AtomicLong();

    private final ThreadLocal<Producer> producers = ThreadLocal.withInitial(this::newProducer);

    public CaptureService(Path ringDirectory, int ringCapacity) {
        this(ringDirectory, ringCapacity, DEFAULT_MAX_CLOCK_DRIFT_MILLIS);
    }

    /**
     * As above, with the clock's drift bound chosen by the caller.
     *
     * <p>The bound exists as a parameter for one reason: a benchmark that publishes as fast as the
     * machine allows exhausts a two-millisecond bound almost immediately and then measures the drop
     * branch while claiming to measure publishing. Raising it lets the measurement traverse the path
     * it names. Production uses {@link #DEFAULT_MAX_CLOCK_DRIFT_MILLIS} and nothing else does.
     */
    public CaptureService(Path ringDirectory, int ringCapacity, long maxClockDriftMillis) {
        this.ringDirectory = ringDirectory;
        this.ringCapacity = ringCapacity;
        this.maxClockDriftMillis = maxClockDriftMillis;
    }

    /** Every ring in use, for the consumer to drain and for recovery to replay. */
    public List<MappedEventRing> rings() {
        return List.copyOf(rings);
    }

    /**
     * Stages a block change seen by an event handler.
     *
     * <p>Called on a tick or region thread. Allocation-free: the staging arrays are preallocated,
     * and nothing here creates an object, boxes a value, formats a string or throws.
     *
     * @param beforeStateId interned state the block had before the event
     */
    public void captureBlockChange(
            int worldId, int x, int y, int z, int beforeStateId, int actorId, int cause, int kind) {
        captured.incrementAndGet();
        if (!EventRecords.positionInRange(x, y, z)) {
            outOfRange.incrementAndGet();
            return;
        }
        long now = System.currentTimeMillis();
        Producer producer = producers.get();
        if (!producer.stage(worldId, x, y, z, beforeStateId, actorId, cause, kind, now)) {
            // The staging buffer is full: publish without confirming rather than lose the record.
            // The rollback engine verifies the world before it touches anything, so an unconfirmed
            // record can only cost a skipped position; losing a real one costs history.
            unconfirmed.incrementAndGet();
            publish(producer, worldId, x, y, z, beforeStateId, beforeStateId, actorId, cause, kind, now);
        }
    }

    /**
     * Publishes the staged records of the calling thread whose blocks actually changed.
     *
     * <p>Called from the tick-end event, which fires on the thread that owns the region, so reading
     * the block needs no scheduling and no chunk load: the chunk was touched moments ago.
     */
    public void confirmStaged(StateReader reader) {
        producers.get().confirm(reader, this);
    }

    /** Publishes everything staged without checking; used when a thread is going away. */
    public void flushStagedUnconfirmed() {
        producers.get().flushUnconfirmed(this);
    }

    void publish(
            Producer producer,
            int worldId,
            int x,
            int y,
            int z,
            int beforeState,
            int afterState,
            int actorId,
            int cause,
            int kind,
            long capturedMillis) {
        long stamp = producer.clock.next(TraceEpoch.toRelative(capturedMillis));
        if (stamp == SlotClock.OVERFLOW) {
            // The slot's clock would have to drift further than ordering allows. Dropping is
            // honest; misdating the record would corrupt the order history is read in.
            droppedSlotOverflow.incrementAndGet();
            producer.ring.countDrop();
            return;
        }
        long position = EventRecords.packPosition(x, y, z);
        long states = EventRecords.packStates(beforeState, afterState, 0);
        long actorTime = EventRecords.packActorTime(SlotClock.millisOf(stamp), actorId);
        long metadata = EventRecords.packMetadata(SlotClock.sequenceOf(stamp), worldId, cause, kind, actorId, 0);
        if (producer.ring.offer(position, states, actorTime, metadata)) {
            published.incrementAndGet();
        } else {
            // M2 has no spill segment yet, so a full ring means a dropped record and a gap. The gap
            // is what makes a later rollback over this window refuse instead of guessing.
            droppedRingFull.incrementAndGet();
            producer.ring.countDrop();
        }
    }

    public long captured() {
        return captured.get();
    }

    public long published() {
        return published.get();
    }

    /** Events that fired but changed nothing — the lava-punch case. */
    public long rejectedUnchanged() {
        return rejectedUnchanged.get();
    }

    public long unconfirmed() {
        return unconfirmed.get();
    }

    /** Records lost, for whatever reason. Each one has to be covered by a gap. */
    public long dropped() {
        return droppedSlotOverflow.get() + droppedRingFull.get();
    }

    /**
     * Dropped because the slot's clock could not stamp them in order.
     *
     * <p>Split from {@link #droppedRingFull()} because they mean different things and need
     * different fixes — and because a test that says it exercises one of them has no way to prove
     * it against a single merged counter.
     */
    public long droppedSlotOverflow() {
        return droppedSlotOverflow.get();
    }

    /** Dropped because the producer's ring had no room and there is no spill segment yet. */
    public long droppedRingFull() {
        return droppedRingFull.get();
    }

    public long outOfRange() {
        return outOfRange.get();
    }

    public long slotsExhausted() {
        return slotsExhausted.get();
    }

    private Producer newProducer() {
        int slot = nextSlot.getAndIncrement();
        if (slot >= MAX_SLOTS) {
            // Every slot is taken; share the last one. Key uniqueness still holds, because it comes
            // from the clock inside the slot, but it is worth knowing about, so it is counted.
            slotsExhausted.incrementAndGet();
            slot = MAX_SLOTS - 1;
        }
        try {
            MappedEventRing ring =
                    MappedEventRing.open(ringDirectory.resolve("r-%02d.ring".formatted(slot)), slot, ringCapacity);
            rings.add(ring);
            return new Producer(ring, new SlotClock(slot, maxClockDriftMillis));
        } catch (IOException e) {
            throw new IllegalStateException("Could not open the capture ring for slot " + slot, e);
        }
    }

    /**
     * Forces and unmaps every ring.
     *
     * <p>Present because a mapped file cannot be deleted on Windows while the mapping is live, so a
     * test using a temporary directory needs a way to let go. The server's shutdown path uses it
     * too, rather than repeating the loop.
     */
    @Override
    public void close() {
        for (MappedEventRing ring : rings) {
            ring.force();
            ring.close();
        }
    }

    /** Reads the state of a position right now, in interned form. */
    @FunctionalInterface
    public interface StateReader {

        /**
         * Reads the interned state at a position.
         *
         * @return the interned state id, or {@link #UNKNOWN_STATE} if it cannot be read from the
         *     calling thread
         */
        int stateAt(int worldId, int x, int y, int z);
    }

    /** One capture thread's ring, clock and staging buffer. */
    static final class Producer {

        final MappedEventRing ring;
        final SlotClock clock;

        private final int[] worldIds = new int[STAGING_CAPACITY];
        private final int[] xs = new int[STAGING_CAPACITY];
        private final int[] ys = new int[STAGING_CAPACITY];
        private final int[] zs = new int[STAGING_CAPACITY];
        private final int[] befores = new int[STAGING_CAPACITY];
        private final int[] actors = new int[STAGING_CAPACITY];
        private final int[] causes = new int[STAGING_CAPACITY];
        private final int[] kinds = new int[STAGING_CAPACITY];
        private final long[] capturedAt = new long[STAGING_CAPACITY];
        private int staged;

        Producer(MappedEventRing ring, SlotClock clock) {
            this.ring = ring;
            this.clock = clock;
        }

        boolean stage(
                int worldId, int x, int y, int z, int before, int actorId, int cause, int kind, long capturedMillis) {
            if (staged == STAGING_CAPACITY) {
                return false;
            }
            int index = staged++;
            worldIds[index] = worldId;
            xs[index] = x;
            ys[index] = y;
            zs[index] = z;
            befores[index] = before;
            actors[index] = actorId;
            causes[index] = cause;
            kinds[index] = kind;
            capturedAt[index] = capturedMillis;
            return true;
        }

        void confirm(StateReader reader, CaptureService service) {
            for (int i = 0; i < staged; i++) {
                int now = reader.stateAt(worldIds[i], xs[i], ys[i], zs[i]);
                if (now == UNKNOWN_STATE) {
                    service.unconfirmed.incrementAndGet();
                    publishStaged(service, i, befores[i]);
                } else if (now == befores[i]) {
                    // The event fired and the world is unchanged: it did not happen.
                    service.rejectedUnchanged.incrementAndGet();
                } else {
                    publishStaged(service, i, now);
                }
            }
            staged = 0;
        }

        void flushUnconfirmed(CaptureService service) {
            for (int i = 0; i < staged; i++) {
                service.unconfirmed.incrementAndGet();
                publishStaged(service, i, befores[i]);
            }
            staged = 0;
        }

        private void publishStaged(CaptureService service, int index, int after) {
            service.publish(
                    this,
                    worldIds[index],
                    xs[index],
                    ys[index],
                    zs[index],
                    befores[index],
                    after,
                    actors[index],
                    causes[index],
                    kinds[index],
                    capturedAt[index]);
        }
    }
}
