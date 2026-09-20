/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.time;

/**
 * The epoch the transient record's 40-bit timestamp counts from.
 *
 * <p>Forty bits of milliseconds is about thirty-four years, which is plenty for a ring, a spill
 * segment or a journal frame — formats that live for seconds and are versioned anyway. Sealed
 * storage keeps absolute milliseconds, so this constant never reaches a shard and a future Trace
 * can change it without a migration.
 */
public final class TraceEpoch {

    /** 2025-01-01T00:00:00Z. */
    public static final long EPOCH_MILLIS = 1_735_689_600_000L;

    /** The last wall-clock millisecond the transient format can represent. */
    public static final long MAX_MILLIS = EPOCH_MILLIS + ((1L << 40) - 1);

    private TraceEpoch() {}

    public static long toRelative(long epochMillis) {
        return epochMillis - EPOCH_MILLIS;
    }

    public static long toAbsolute(long relativeMillis) {
        return relativeMillis + EPOCH_MILLIS;
    }

    public static boolean representable(long epochMillis) {
        return epochMillis >= EPOCH_MILLIS && epochMillis <= MAX_MILLIS;
    }
}
