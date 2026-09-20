/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.time;

/**
 * The per-slot clock that makes event keys unique.
 *
 * <p>A slot has exactly one owning thread at a time, so a counter kept inside the slot, combined
 * with the slot's own id, is enough to make {@code (world, chunk, timestamp, sequence)} unique
 * without any coordination between producers — no shared counter, no contended cache line on the
 * hottest path in the plugin. See ADR-0011.
 *
 * <p>The interesting case is a millisecond that runs out of counter values. A logical clock may
 * step ahead of the wall clock to keep going, but only by {@link #maxDriftMillis()}: unbounded
 * borrowing would let a later event on an undrifted thread carry a smaller timestamp than an
 * earlier event here, silently inverting history. Past the bound {@link #next(long)} returns
 * {@link #OVERFLOW} and the caller spills the event instead of misdating it.
 *
 * <p>Not thread-safe by construction: one slot, one thread. Allocation-free.
 */
public final class SlotClock {

    /** Bits of counter inside the 16-bit sequence field; the rest identify the slot. */
    public static final int COUNTER_BITS = 10;

    public static final int MAX_COUNTER = (1 << COUNTER_BITS) - 1;

    public static final int SLOT_BITS = 6;

    public static final int MAX_SLOT = (1 << SLOT_BITS) - 1;

    /** Returned when the clock would have to drift further than it is allowed to. */
    public static final long OVERFLOW = -1L;

    private final int slot;
    private final long maxDriftMillis;

    private long logicalMillis = -1L;
    private int counter;

    /**
     * @param slot producer slot id, 0..{@value #MAX_SLOT}
     * @param maxDriftMillis how far the logical clock may run ahead of the wall clock; a small
     *     fraction of a tick, so that ordering across threads stays meaningful
     */
    public SlotClock(int slot, long maxDriftMillis) {
        if (slot < 0 || slot > MAX_SLOT) {
            throw new IllegalArgumentException("Slot out of range: " + slot);
        }
        if (maxDriftMillis < 0) {
            throw new IllegalArgumentException("Negative drift bound: " + maxDriftMillis);
        }
        this.slot = slot;
        this.maxDriftMillis = maxDriftMillis;
    }

    public int slot() {
        return slot;
    }

    public long maxDriftMillis() {
        return maxDriftMillis;
    }

    /** The clock's current logical millisecond, which may be ahead of the wall clock. */
    public long logicalMillis() {
        return logicalMillis;
    }

    /** How far ahead of {@code nowMillis} this clock currently is. */
    public long driftMillis(long nowMillis) {
        return Math.max(0L, logicalMillis - nowMillis);
    }

    /**
     * Ensures the clock never goes backwards across a restart or a slot handover.
     *
     * @param atLeastMillis the highest logical millisecond previously seen for this slot
     */
    public void seed(long atLeastMillis) {
        if (atLeastMillis > logicalMillis) {
            logicalMillis = atLeastMillis;
            counter = MAX_COUNTER;
        }
    }

    /**
     * Takes the next timestamp and sequence for this slot.
     *
     * @param nowMillis the wall clock, relative to the Trace epoch
     * @return {@code millis << 16 | sequence}, or {@link #OVERFLOW} when the drift bound is reached
     */
    public long next(long nowMillis) {
        if (nowMillis > logicalMillis) {
            logicalMillis = nowMillis;
            counter = 0;
        } else if (counter < MAX_COUNTER) {
            counter++;
        } else if (logicalMillis + 1 - nowMillis > maxDriftMillis) {
            return OVERFLOW;
        } else {
            logicalMillis++;
            counter = 0;
        }
        return (logicalMillis << 16) | ((long) counter << SLOT_BITS) | slot;
    }

    /** The millisecond part of a value returned by {@link #next(long)}. */
    public static long millisOf(long stamp) {
        return stamp >>> 16;
    }

    /** The sequence part of a value returned by {@link #next(long)}. */
    public static int sequenceOf(long stamp) {
        return (int) (stamp & 0xFFFF);
    }

    /** The slot that produced a sequence. */
    public static int slotOf(int sequence) {
        return sequence & MAX_SLOT;
    }
}
