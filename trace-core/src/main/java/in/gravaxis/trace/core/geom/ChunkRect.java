/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

/**
 * An inclusive rectangle of chunk coordinates: the horizontal footprint of a query.
 *
 * @param minChunkX western edge, inclusive
 * @param minChunkZ northern edge, inclusive
 * @param maxChunkX eastern edge, inclusive
 * @param maxChunkZ southern edge, inclusive
 */
public record ChunkRect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    public ChunkRect {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException(
                    "Empty chunk rectangle: (%d,%d)..(%d,%d)".formatted(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        }
        if (!Morton.chunkInRange(minChunkX, minChunkZ) || !Morton.chunkInRange(maxChunkX, maxChunkZ)) {
            throw new IllegalArgumentException("Chunk rectangle outside the addressable world: (%d,%d)..(%d,%d)"
                    .formatted(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        }
    }

    /** A rectangle covering one chunk. */
    public static ChunkRect of(int chunkX, int chunkZ) {
        return new ChunkRect(chunkX, chunkZ, chunkX, chunkZ);
    }

    /** The rectangle covering a block-coordinate range, inclusive at both ends. */
    public static ChunkRect ofBlocks(int minX, int minZ, int maxX, int maxZ) {
        return new ChunkRect(
                Math.min(minX, maxX) >> 4,
                Math.min(minZ, maxZ) >> 4,
                Math.max(minX, maxX) >> 4,
                Math.max(minZ, maxZ) >> 4);
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public long chunkCount() {
        return (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
    }
}
