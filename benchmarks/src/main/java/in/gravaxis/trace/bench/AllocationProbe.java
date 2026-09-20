/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

/**
 * The shape of the capture hot path, isolated so that two very different instruments can measure the
 * same code: JMH's GC profiler and an exact per-thread allocation counter.
 *
 * <p>This is measurement apparatus, not the real encoder. M2 replaces the body with a call into
 * {@code trace-core}'s record codec; what must not change is that both instruments keep pointing at
 * whatever the tick thread actually runs.
 */
public final class AllocationProbe {

    /** Longs per record: position, state pair, actor and time, sequence and sidecar. */
    public static final int LONGS_PER_RECORD = 4;

    private AllocationProbe() {}

    /**
     * Packs one block change into four longs and writes it into a preallocated ring slot.
     *
     * <p>No objects, no boxing, no varargs, no exceptions: the only way this allocates is if someone
     * changes it to.
     *
     * @param buffer the ring, whose length must be a power of two
     * @param cursor the slot to write at
     * @param counter a varying input, so the JIT cannot fold the work away
     * @return the next cursor position
     */
    public static int encodeInto(long[] buffer, int cursor, int counter) {
        int x = counter & 0x1FFFFFF;
        int z = (counter * 31) & 0x1FFFFFF;
        int y = (counter & 0x7F) - 64;
        int beforeState = counter & 0xFFFF;
        int afterState = (counter + 1) & 0xFFFF;
        int actor = counter & 0xFF;

        long position = ((long) x << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        long states = ((long) beforeState << 32) | (afterState & 0xFFFFFFFFL);
        long actorAndTime = ((long) counter << 24) | actor;
        long sequence = ((long) (counter & 0xFFFF) << 48) | (counter & 0xFFFFL);

        buffer[cursor] = position;
        buffer[cursor + 1] = states;
        buffer[cursor + 2] = actorAndTime;
        buffer[cursor + 3] = sequence;
        return (cursor + LONGS_PER_RECORD) & (buffer.length - 1);
    }
}
