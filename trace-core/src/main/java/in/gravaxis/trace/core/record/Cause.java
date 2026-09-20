/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.record;

/**
 * Why a change happened.
 *
 * <p>The numeric id is persisted, so it is assigned once and never reused for something else. Only
 * the causes M2 can actually produce are defined here; the rest of the matrix in the build spec
 * arrives with the capture milestone that implements it, each with the next free id.
 */
public enum Cause {
    /** Unknown or not yet attributed. Never written deliberately; a zero id means "nobody set it". */
    UNKNOWN(0),

    /** A player broke a block. */
    BREAKING(1),

    /** A player placed a block. */
    PLACING(2),

    /** Trace itself changed the world while rolling something back. */
    ROLLBACK(3);

    private static final Cause[] BY_ID = buildLookup();

    private final int id;

    Cause(int id) {
        this.id = id;
    }

    /** The persisted id. Stable forever. */
    public int id() {
        return id;
    }

    /** The cause for a persisted id, or {@link #UNKNOWN} for an id this build does not know. */
    public static Cause byId(int id) {
        if (id < 0 || id >= BY_ID.length) {
            return UNKNOWN;
        }
        Cause cause = BY_ID[id];
        return cause == null ? UNKNOWN : cause;
    }

    private static Cause[] buildLookup() {
        Cause[] values = values();
        int max = 0;
        for (Cause cause : values) {
            max = Math.max(max, cause.id);
        }
        Cause[] lookup = new Cause[max + 1];
        for (Cause cause : values) {
            lookup[cause.id] = cause;
        }
        return lookup;
    }
}
