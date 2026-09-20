/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

/**
 * Z-order (Morton) chunk keys: the sort order the sealed shards are clustered by.
 *
 * <p>Clustering by interleaved chunk coordinates is what turns a cuboid rollback into a handful of
 * contiguous range scans instead of an index lookup per position. Chunk coordinates are biased into
 * unsigned space first, which keeps the mapping monotone so that range decomposition stays valid
 * across the origin — a signed interleave would put chunk (-1, -1) above chunk (1, 1).
 *
 * <p>Chunk coordinates run to about +-1.875 M (the world border divided by sixteen), so 22 bits per
 * axis is enough and the key is 44 bits: comfortably inside a SQLite integer, with room for the
 * section index the patch format will add.
 */
public final class Morton {

    /** Bits per axis after biasing. */
    public static final int AXIS_BITS = 22;

    /** Added to a signed chunk coordinate to make it unsigned. */
    public static final int BIAS = 1 << (AXIS_BITS - 1);

    public static final int MIN_CHUNK = -BIAS;

    public static final int MAX_CHUNK = BIAS - 1;

    private Morton() {}

    public static boolean chunkInRange(int chunkX, int chunkZ) {
        return chunkX >= MIN_CHUNK && chunkX <= MAX_CHUNK && chunkZ >= MIN_CHUNK && chunkZ <= MAX_CHUNK;
    }

    /** Interleaves a chunk coordinate pair into a 44-bit key, x in the even bits. */
    public static long key(int chunkX, int chunkZ) {
        return spread(chunkX + BIAS) | (spread(chunkZ + BIAS) << 1);
    }

    public static int chunkX(long key) {
        return (int) compact(key) - BIAS;
    }

    public static int chunkZ(long key) {
        return (int) compact(key >>> 1) - BIAS;
    }

    /**
     * Spreads 22 low bits so that each occupies every second bit.
     *
     * <p>Magic-number shifts rather than {@code PDEP}: the Vector API is off the table (it still
     * needs {@code --add-modules}), and BMI2's {@code PDEP} is microcoded and slow on the Zen 1 and
     * Zen 2 processors plenty of servers still run.
     */
    private static long spread(long value) {
        long x = value & 0x3FFFFFL;
        x = (x | (x << 16)) & 0x0000_FFFF_0000_FFFFL;
        x = (x | (x << 8)) & 0x00FF_00FF_00FF_00FFL;
        x = (x | (x << 4)) & 0x0F0F_0F0F_0F0F_0F0FL;
        x = (x | (x << 2)) & 0x3333_3333_3333_3333L;
        x = (x | (x << 1)) & 0x5555_5555_5555_5555L;
        return x;
    }

    private static long compact(long value) {
        long x = value & 0x5555_5555_5555_5555L;
        x = (x | (x >>> 1)) & 0x3333_3333_3333_3333L;
        x = (x | (x >>> 2)) & 0x0F0F_0F0F_0F0F_0F0FL;
        x = (x | (x >>> 4)) & 0x00FF_00FF_00FF_00FFL;
        x = (x | (x >>> 8)) & 0x0000_FFFF_0000_FFFFL;
        x = (x | (x >>> 16)) & 0x0000_0000_FFFF_FFFFL;
        return x;
    }
}
