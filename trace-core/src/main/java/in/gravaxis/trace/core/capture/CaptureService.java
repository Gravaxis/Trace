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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

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

    /** Producer slots; excess threads take an explicit loss path, never share an SPSC ring. */
    public static final int MAX_SLOTS = 32;

    /** Distinct positions a thread can stage within one tick before further positions are gapped. */
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
    private final List<Producer> liveProducers = new CopyOnWriteArrayList<>();

    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong rejectedUnchanged = new AtomicLong();
    private final AtomicLong unconfirmed = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong droppedStagingFull = new AtomicLong();
    private final AtomicLong droppedUnreadable = new AtomicLong();
    private final AtomicLong droppedUnconfirmedFlush = new AtomicLong();
    private final AtomicLong droppedAmbiguous = new AtomicLong();
    private final AtomicLong invalidFields = new AtomicLong();
    private final AtomicLong droppedSlotOverflow = new AtomicLong();
    private final AtomicLong droppedRingFull = new AtomicLong();
    private final AtomicLong outOfRange = new AtomicLong();
    private final AtomicLong slotsExhausted = new AtomicLong();
    private final AtomicLong droppedNoSlot = new AtomicLong();
    private final CaptureLoss losses;

    private final ThreadLocal<@Nullable Producer> producers = ThreadLocal.withInitial(this::newProducer);

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
        try {
            losses = CaptureLoss.open(ringDirectory.resolve("capture.loss"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not open capture loss marker", e);
        }
    }

    /** Every ring in use, for the consumer to drain and for recovery to replay. */
    public List<MappedEventRing> rings() {
        return List.copyOf(rings);
    }

    /**
     * The oldest capture time still held in staging anywhere, or {@link Long#MAX_VALUE} if nothing
     * is staged.
     *
     * <p>This is what bounds a crash gap from below. A staged record has not reached a ring, a
     * journal or the store, so a crash takes it; and because each producer stages independently, a
     * record staged on one region thread can be older than everything another thread has already
     * journalled. A gap that began at the newest journalled capture time would start <em>after</em>
     * an event it has to cover, which is worse than having no gap at all: a rollback would run over
     * that moment believing the history complete.
     *
     * <p>Read by the consumer thread, written by producers. The value is a snapshot and may be
     * stale by the time it is used, but only in the safe direction: a producer that stages after
     * this returns is captured in a later frame's mark, and a producer that confirms after it only
     * makes the mark wider than it needed to be.
     */
    public long oldestStagedMillis() {
        long oldest = Long.MAX_VALUE;
        for (Producer producer : producers()) {
            long staged = producer.oldestStagedMillis;
            if (staged < oldest) {
                oldest = staged;
            }
        }
        return oldest;
    }

    /** Every producer, for the consumer to ask about staging. */
    private List<Producer> producers() {
        return List.copyOf(liveProducers);
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
            losses.record(System.currentTimeMillis() + maxClockDriftMillis);
            return;
        }
        if (worldId < 0
                || worldId > EventRecords.MAX_WORLD_ID
                || !stateInRange(beforeStateId)
                || cause < 0
                || cause > 0xFF
                || kind < 0
                || kind > 0xF) {
            invalidFields.incrementAndGet();
            losses.record(System.currentTimeMillis() + maxClockDriftMillis);
            return;
        }
        long now = System.currentTimeMillis();
        Producer producer = producers.get();
        if (producer == null) {
            losses.record(now + maxClockDriftMillis);
            droppedNoSlot.incrementAndGet();
            return;
        }
        if (!producer.stage(worldId, x, y, z, beforeStateId, actorId, cause, kind, now)) {
            // A before=after row would claim knowledge we do not have. ADR-0024 replaces that
            // fallback with a gap, which makes rollback refuse the uncertain window.
            unconfirmed.incrementAndGet();
            droppedStagingFull.incrementAndGet();
            losses.record(now + maxClockDriftMillis);
        } else if (producer.lastStageCoalesced) {
            coalesced.incrementAndGet();
        }
    }

    /**
     * Publishes the staged records of the calling thread whose blocks actually changed.
     *
     * <p>Called from the tick-end event, which fires on the thread that owns the region, so reading
     * the block needs no scheduling and no chunk load: the chunk was touched moments ago.
     */
    public void confirmStaged(StateReader reader) {
        Producer producer = producers.get();
        if (producer != null) producer.confirm(reader, this);
    }

    /** Discards staged observations with persistent loss bounds when confirmation is impossible. */
    public void flushStagedUnconfirmed() {
        Producer producer = producers.get();
        if (producer != null) producer.flushUnconfirmed(this);
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
            losses.record(Math.max(System.currentTimeMillis(), capturedMillis) + maxClockDriftMillis);
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
            losses.record(Math.max(System.currentTimeMillis(), capturedMillis) + maxClockDriftMillis);
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

    public long coalesced() {
        return coalesced.get();
    }

    public long droppedStagingFull() {
        return droppedStagingFull.get();
    }

    public long droppedUnreadable() {
        return droppedUnreadable.get();
    }

    public long droppedUnconfirmedFlush() {
        return droppedUnconfirmedFlush.get();
    }

    public long droppedAmbiguous() {
        return droppedAmbiguous.get();
    }

    public long invalidFields() {
        return invalidFields.get();
    }

    long indexCollisions() {
        Producer producer = producers.get();
        return producer == null ? 0 : producer.indexCollisions;
    }

    private static boolean stateInRange(int state) {
        return state >= 0 && state <= EventRecords.MAX_STATE_ID;
    }

    /** Records lost, for whatever reason. Each one has to be covered by a gap. */
    public long dropped() {
        return droppedSlotOverflow.get()
                + droppedRingFull.get()
                + droppedNoSlot.get()
                + droppedStagingFull.get()
                + droppedUnreadable.get()
                + droppedUnconfirmedFlush.get()
                + droppedAmbiguous.get()
                + invalidFields.get()
                + outOfRange.get();
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

    public long droppedNoSlot() {
        return droppedNoSlot.get();
    }

    public long lossFromMillis() {
        return losses.fromMillis();
    }

    public long lossToMillis() {
        return losses.toMillis();
    }

    public long lossCount() {
        return losses.count();
    }

    private @Nullable Producer newProducer() {
        int slot = nextSlot.getAndIncrement();
        if (slot >= MAX_SLOTS) {
            slotsExhausted.incrementAndGet();
            return null;
        }
        try {
            MappedEventRing ring =
                    MappedEventRing.open(ringDirectory.resolve("r-%02d.ring".formatted(slot)), slot, ringCapacity);
            rings.add(ring);
            Producer producer = new Producer(ring, new SlotClock(slot, maxClockDriftMillis));
            liveProducers.add(producer);
            return producer;
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
        losses.close();
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
        private final int[] observations = new int[STAGING_CAPACITY];
        private final boolean[] ambiguous = new boolean[STAGING_CAPACITY];
        private final int[] index = new int[STAGING_CAPACITY * 2];
        private boolean lastStageCoalesced;
        private long indexCollisions;
        private int staged;

        /**
         * The capture time of the oldest record staged here, or {@link Long#MAX_VALUE} for none.
         *
         * <p>Volatile because the consumer thread reads it while this producer's thread writes it.
         * Written twice per tick rather than per record: once when staging starts, once when it
         * empties.
         */
        private volatile long oldestStagedMillis = Long.MAX_VALUE;

        Producer(MappedEventRing ring, SlotClock clock) {
            this.ring = ring;
            this.clock = clock;
        }

        boolean stage(
                int worldId, int x, int y, int z, int before, int actorId, int cause, int kind, long capturedMillis) {
            lastStageCoalesced = false;
            long hash = EventRecords.packPosition(x, y, z) ^ ((long) worldId * 0x9E3779B97F4A7C15L);
            hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
            hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
            int bucket = (int) (hash ^ (hash >>> 31)) & (index.length - 1);
            while (index[bucket] != 0) {
                int previous = index[bucket] - 1;
                if (worldIds[previous] == worldId && xs[previous] == x && ys[previous] == y && zs[previous] == z) {
                    observations[previous]++;
                    ambiguous[previous] |=
                            actors[previous] != actorId || causes[previous] != cause || kinds[previous] != kind;
                    lastStageCoalesced = true;
                    return true;
                }
                indexCollisions++;
                bucket = (bucket + 1) & (index.length - 1);
            }
            if (staged == STAGING_CAPACITY) {
                return false;
            }
            if (staged == 0) {
                oldestStagedMillis = capturedMillis;
            }
            int next = staged++;
            index[bucket] = next + 1;
            worldIds[next] = worldId;
            xs[next] = x;
            ys[next] = y;
            zs[next] = z;
            befores[next] = before;
            actors[next] = actorId;
            causes[next] = cause;
            kinds[next] = kind;
            capturedAt[next] = capturedMillis;
            observations[next] = 1;
            ambiguous[next] = false;
            return true;
        }

        void confirm(StateReader reader, CaptureService service) {
            for (int i = 0; i < staged; i++) {
                int now = reader.stateAt(worldIds[i], xs[i], ys[i], zs[i]);
                if (now == UNKNOWN_STATE) {
                    service.unconfirmed.addAndGet(observations[i]);
                    lose(service, i, service.droppedUnreadable);
                } else if (!stateInRange(now)) {
                    lose(service, i, service.invalidFields);
                } else if (now == befores[i]) {
                    // The event fired and the world is unchanged: it did not happen.
                    service.rejectedUnchanged.addAndGet(observations[i]);
                } else if (ambiguous[i]) {
                    lose(service, i, service.droppedAmbiguous);
                } else {
                    publishStaged(service, i, now);
                }
            }
            staged = 0;
            Arrays.fill(index, 0);
            oldestStagedMillis = Long.MAX_VALUE;
        }

        void flushUnconfirmed(CaptureService service) {
            for (int i = 0; i < staged; i++) {
                service.unconfirmed.addAndGet(observations[i]);
                lose(service, i, service.droppedUnconfirmedFlush);
            }
            staged = 0;
            Arrays.fill(index, 0);
            oldestStagedMillis = Long.MAX_VALUE;
        }

        private void lose(CaptureService service, int index, AtomicLong counter) {
            counter.addAndGet(observations[index]);
            for (int n = 0; n < observations[index]; n++) {
                service.losses.record(
                        Math.max(System.currentTimeMillis(), capturedAt[index]) + service.maxClockDriftMillis);
            }
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
