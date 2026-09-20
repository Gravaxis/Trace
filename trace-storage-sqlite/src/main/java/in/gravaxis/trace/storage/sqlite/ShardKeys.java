/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

/**
 * How a row's time and position are packed into shard columns.
 *
 * <p>Two packings, both narrow on purpose. {@code k} folds the timestamp and the intra-millisecond
 * sequence into one integer so that the primary key is three columns instead of four and a time
 * window becomes a single {@code BETWEEN} — the timestamp is stored relative to the shard's base so
 * it stays comfortably inside a signed 64-bit integer. {@code p} folds the position within the
 * chunk into twenty bits, because the chunk itself is already in the key.
 */
final class ShardKeys {

    /** Bits of sequence inside the packed key. */
    private static final int SEQUENCE_BITS = 16;

    private static final int Y_BIAS = 2048;

    private ShardKeys() {}

    static long key(long baseMillis, long timestamp, int sequence) {
        return ((timestamp - baseMillis) << SEQUENCE_BITS) | (sequence & 0xFFFFL);
    }

    static long timestampOf(long baseMillis, long key) {
        return (key >>> SEQUENCE_BITS) + baseMillis;
    }

    static int sequenceOf(long key) {
        return (int) (key & 0xFFFFL);
    }

    /** The lowest key that can hold {@code timestamp}. */
    static long lowKey(long baseMillis, long timestamp) {
        return (timestamp - baseMillis) << SEQUENCE_BITS;
    }

    /** The highest key that can hold {@code timestamp}. */
    static long highKey(long baseMillis, long timestamp) {
        return ((timestamp - baseMillis) << SEQUENCE_BITS) | 0xFFFFL;
    }

    static int localPosition(int x, int y, int z) {
        return ((x & 15) << 16) | ((z & 15) << 12) | ((y + Y_BIAS) & 0xFFF);
    }

    static int localX(int packed) {
        return (packed >>> 16) & 15;
    }

    static int localZ(int packed) {
        return (packed >>> 12) & 15;
    }

    static int y(int packed) {
        return (packed & 0xFFF) - Y_BIAS;
    }
}
