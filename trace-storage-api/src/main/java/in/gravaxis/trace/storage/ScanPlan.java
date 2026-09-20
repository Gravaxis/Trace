/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.ChunkRect;
import in.gravaxis.trace.core.geom.MortonRange;
import in.gravaxis.trace.core.geom.MortonRanges;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What to read, expressed so that every backend can compile it into its own scan.
 *
 * <p>This is the seam the build spec's tiers hang from: the embedded SQLite default and a
 * ClickHouse cluster take the same plan and run the same rollback engine above it. If a second
 * query planner ever appears, this type is wrong.
 *
 * @param worldId dictionary id of the world to search
 * @param ranges Morton key ranges to scan, in ascending order; may be coarse
 * @param box the cuboid the caller actually asked about — always applied to rows the ranges return
 * @param fromMillis start of the time window, inclusive
 * @param toMillis end of the time window, exclusive
 * @param actorIds actors to keep, or null for all of them
 * @param order the order rows are returned in
 * @param batchSize rows per page; keyset pagination, never an offset
 */
public record ScanPlan(
        int worldId,
        List<MortonRange> ranges,
        BlockBox box,
        long fromMillis,
        long toMillis,
        int @Nullable [] actorIds,
        Order order,
        int batchSize) {

    /** Direction of a scan. */
    public enum Order {
        /**
         * Newest first. What a rollback wants: the first row seen for a position is the state to
         * expect in the world, and the oldest row reached is the state to restore.
         */
        NEWEST_FIRST,

        /** Oldest first. What a restore and an audit listing want. */
        OLDEST_FIRST
    }

    public static final int DEFAULT_BATCH_SIZE = 4096;

    public ScanPlan {
        if (fromMillis > toMillis) {
            throw new IllegalArgumentException("Inverted time window: " + fromMillis + " > " + toMillis);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        ranges = List.copyOf(ranges);
        actorIds = actorIds == null ? null : actorIds.clone();
    }

    /** A plan covering a cuboid and a time window, with ranges derived from the box. */
    public static ScanPlan of(int worldId, BlockBox box, long fromMillis, long toMillis, Order order) {
        ChunkRect rect = box.chunks();
        return new ScanPlan(
                worldId, MortonRanges.decompose(rect), box, fromMillis, toMillis, null, order, DEFAULT_BATCH_SIZE);
    }

    public ScanPlan withActors(int... actors) {
        return new ScanPlan(worldId, ranges, box, fromMillis, toMillis, actors, order, batchSize);
    }

    public ScanPlan withBatchSize(int size) {
        return new ScanPlan(worldId, ranges, box, fromMillis, toMillis, actorIds, order, size);
    }

    /** True when {@code actorId} passes this plan's actor filter. */
    public boolean acceptsActor(int actorId) {
        int[] actors = actorIds;
        if (actors == null) {
            return true;
        }
        for (int candidate : actors) {
            if (candidate == actorId) {
                return true;
            }
        }
        return false;
    }

    /** True when the plan reads more chunks than it needs, so rows must be post-filtered. */
    public boolean coarse() {
        return ranges.stream().anyMatch(MortonRange::coarse) || !box.chunkAligned();
    }
}
