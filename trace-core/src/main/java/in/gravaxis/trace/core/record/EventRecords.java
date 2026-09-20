/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.record;

/**
 * The 32-byte capture record: four longs, packed and unpacked without allocating.
 *
 * <p>This is the format the tick thread writes and everything downstream reads, so every method
 * here is a handful of shifts and masks. Nothing in this class allocates, throws, or reads a field
 * it was not given — gate P1 measures exactly this code path (see ADR-0009), and the layout itself
 * is ADR-0011.
 *
 * <pre>
 * L0  63..38 x (s26)   37..12 z (s26)    11..0 y (s12)
 * L1  63..40 before    39..16 after      15..0 aux
 * L2  63..24 tRel      23..0  actorLo
 * L3  63..48 seq  47..36 world  35..28 cause  27..24 kind  23..16 actorHi  15..0 sidecar
 * </pre>
 *
 * <p>Ranges are checked with {@link #positionInRange(int, int, int)} rather than clamped: writing a
 * wrong coordinate quietly is the class of defect this project exists to avoid.
 */
public final class EventRecords {

    /** Longs per record. */
    public static final int LONGS = 4;

    /** Bytes per record. */
    public static final int BYTES = LONGS * Long.BYTES;

    /** Horizontal coordinates: 26 signed bits, covering +-33,554,432 around the world border. */
    public static final int MIN_HORIZONTAL = -(1 << 25);

    public static final int MAX_HORIZONTAL = (1 << 25) - 1;

    /** Vertical coordinate: 12 signed bits, covering the datapack-legal build range and more. */
    public static final int MIN_Y = -(1 << 11);

    public static final int MAX_Y = (1 << 11) - 1;

    /** Interned block-state ids are 24 bits in the transient record. */
    public static final int MAX_STATE_ID = (1 << 24) - 1;

    /** Actor ids are 32 bits, split across two words. */
    public static final long MAX_ACTOR_ID = 0xFFFFFFFFL;

    /** Worlds are a 12-bit dictionary id. */
    public static final int MAX_WORLD_ID = (1 << 12) - 1;

    /** Milliseconds since the Trace epoch: 40 bits, about 34 years. */
    public static final long MAX_RELATIVE_MILLIS = (1L << 40) - 1;

    public static final int MAX_SEQUENCE = 0xFFFF;

    public static final int MAX_AUX = 0xFFFF;

    public static final int MAX_SIDECAR = 0xFFFF;

    private static final int X_SHIFT = 38;
    private static final int Z_SHIFT = 12;
    private static final long Y_MASK = 0xFFFL;
    private static final long H_MASK = 0x3FFFFFFL;

    private EventRecords() {}

    // --- position -------------------------------------------------------------------------------

    public static boolean positionInRange(int x, int y, int z) {
        return x >= MIN_HORIZONTAL
                && x <= MAX_HORIZONTAL
                && z >= MIN_HORIZONTAL
                && z <= MAX_HORIZONTAL
                && y >= MIN_Y
                && y <= MAX_Y;
    }

    public static long packPosition(int x, int y, int z) {
        return ((long) x << X_SHIFT) | ((z & H_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int x(long position) {
        return (int) (position >> X_SHIFT);
    }

    public static int z(long position) {
        return (int) (position << (64 - X_SHIFT) >> (64 - 26));
    }

    public static int y(long position) {
        return (int) (position << 52 >> 52);
    }

    // --- states ---------------------------------------------------------------------------------

    public static long packStates(int beforeState, int afterState, int aux) {
        return ((long) beforeState << 40) | ((long) afterState << 16) | (aux & 0xFFFFL);
    }

    public static int beforeState(long states) {
        return (int) (states >>> 40);
    }

    public static int afterState(long states) {
        return (int) ((states >>> 16) & 0xFFFFFFL);
    }

    public static int aux(long states) {
        return (int) (states & 0xFFFFL);
    }

    // --- time and actor -------------------------------------------------------------------------

    public static long packActorTime(long relativeMillis, int actorId) {
        return (relativeMillis << 24) | (actorId & 0xFFFFFFL);
    }

    public static long relativeMillis(long actorTime) {
        return actorTime >>> 24;
    }

    /** The low 24 bits of the actor id; the high 8 live in the metadata word. */
    public static int actorLow(long actorTime) {
        return (int) (actorTime & 0xFFFFFFL);
    }

    // --- metadata -------------------------------------------------------------------------------

    public static long packMetadata(int sequence, int worldId, int cause, int kind, int actorId, int sidecar) {
        return ((long) (sequence & 0xFFFF) << 48)
                | ((long) (worldId & 0xFFF) << 36)
                | ((long) (cause & 0xFF) << 28)
                | ((long) (kind & 0xF) << 24)
                | ((long) ((actorId >>> 24) & 0xFF) << 16)
                | (sidecar & 0xFFFFL);
    }

    public static int sequence(long metadata) {
        return (int) (metadata >>> 48);
    }

    public static int worldId(long metadata) {
        return (int) ((metadata >>> 36) & 0xFFF);
    }

    public static int cause(long metadata) {
        return (int) ((metadata >>> 28) & 0xFF);
    }

    public static int kind(long metadata) {
        return (int) ((metadata >>> 24) & 0xF);
    }

    public static int sidecar(long metadata) {
        return (int) (metadata & 0xFFFF);
    }

    /** Reassembles the 32-bit actor id from the two words that carry it. */
    public static int actorId(long actorTime, long metadata) {
        return (int) (((metadata >>> 16) & 0xFFL) << 24 | (actorTime & 0xFFFFFFL));
    }
}
