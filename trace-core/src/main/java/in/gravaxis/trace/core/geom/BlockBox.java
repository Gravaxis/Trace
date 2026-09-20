/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

import in.gravaxis.trace.core.record.EventRecords;

/**
 * An inclusive cuboid of block positions: what a query actually asks about.
 *
 * <p>The chunk rectangle derived from it decides which key ranges get scanned; this box decides
 * which of the rows those ranges return are kept. Keeping both, rather than trusting the ranges, is
 * what lets the range cover be coarse without ever being wrong.
 */
public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public BlockBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Inverted box: (%d,%d,%d)..(%d,%d,%d)".formatted(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    /** The whole addressable world. */
    public static BlockBox everything() {
        return new BlockBox(
                EventRecords.MIN_HORIZONTAL,
                EventRecords.MIN_Y,
                EventRecords.MIN_HORIZONTAL,
                EventRecords.MAX_HORIZONTAL,
                EventRecords.MAX_Y,
                EventRecords.MAX_HORIZONTAL);
    }

    /** A cube of {@code radius} blocks around a centre, clamped to the addressable world. */
    public static BlockBox around(int x, int y, int z, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("Negative radius: " + radius);
        }
        return new BlockBox(
                clampHorizontal((long) x - radius),
                clampVertical((long) y - radius),
                clampHorizontal((long) z - radius),
                clampHorizontal((long) x + radius),
                clampVertical((long) y + radius),
                clampHorizontal((long) z + radius));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** The chunks this box touches. */
    public ChunkRect chunks() {
        return ChunkRect.ofBlocks(minX, minZ, maxX, maxZ);
    }

    /** True when the box covers every block of every chunk it touches, so no post-filter is needed. */
    public boolean chunkAligned() {
        return (minX & 15) == 0
                && (minZ & 15) == 0
                && (maxX & 15) == 15
                && (maxZ & 15) == 15
                && minY <= EventRecords.MIN_Y
                && maxY >= EventRecords.MAX_Y;
    }

    private static int clampHorizontal(long value) {
        return (int) Math.clamp(value, EventRecords.MIN_HORIZONTAL, EventRecords.MAX_HORIZONTAL);
    }

    private static int clampVertical(long value) {
        return (int) Math.clamp(value, EventRecords.MIN_Y, EventRecords.MAX_Y);
    }
}
