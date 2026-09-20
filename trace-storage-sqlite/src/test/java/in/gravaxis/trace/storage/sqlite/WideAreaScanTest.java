/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.ChunkRect;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.core.geom.MortonRange;
import in.gravaxis.trace.core.geom.MortonRanges;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scan over an area big enough that the Morton cover cannot be exact.
 *
 * <p>This is the shape a real rollback builds — a radius around a point, spanning thousands of
 * chunks, with data in two of them — and it is deliberately separate from the small boxes the
 * contract suite uses. The integration test found a wide-area scan returning nothing at all while
 * every small-box test passed, so the wide case is pinned here where it fails in a second instead of
 * in a server run.
 */
class WideAreaScanTest {

    private static final int WORLD = 1;
    private static final long T0 = 1_800_000_000_000L;
    private static final int Y = -54;
    private static final int PER_CLUSTER = 12;

    /** The two places the events are, 640 blocks apart, and the box that has to reach both. */
    private static final int[][] CLUSTERS = {{0, 0}, {640, 640}};

    private static final int CENTRE = 320;
    private static final int RADIUS = 400;

    @TempDir
    Path directory;

    @Test
    @DisplayName("a wide box still covers the chunks that hold the rows")
    void coversBothChunks() {
        ChunkRect rect = BlockBox.around(CENTRE, Y, CENTRE, RADIUS).chunks();
        List<MortonRange> ranges = MortonRanges.decompose(rect);

        assertThat(rect.contains(0, 0)).isTrue();
        assertThat(rect.contains(40, 40)).isTrue();
        assertThat(covers(ranges, Morton.key(0, 0)))
                .as("chunk 0,0 is inside the box but no range reaches its key: %s", ranges)
                .isTrue();
        assertThat(covers(ranges, Morton.key(40, 40)))
                .as("chunk 40,40 is inside the box but no range reaches its key: %s", ranges)
                .isTrue();
    }

    @Test
    @DisplayName("a wide scan returns every row in both clusters")
    void readsBothClusters() throws StoreException {
        List<Events> events = new ArrayList<>();
        int sequence = 0;
        for (int[] cluster : CLUSTERS) {
            for (int i = 0; i < PER_CLUSTER; i++) {
                events.add(new Events(
                        WORLD, cluster[0] + i, Y, cluster[1], T0 + sequence, sequence, 3, 0, 7, Cause.BREAKING));
                sequence++;
            }
        }

        try (EventStore store = SqliteEventStore.open(directory)) {
            RecordBatch batch = new RecordBatch(events.size());
            events.forEach(event -> event.addTo(batch));
            store.append(batch, 0);

            ScanPlan plan = ScanPlan.of(
                    WORLD,
                    BlockBox.around(CENTRE, Y, CENTRE, RADIUS),
                    T0 - 120_000,
                    T0 + 120_000,
                    ScanPlan.Order.NEWEST_FIRST);

            assertThat(count(store, plan)).as("before sealing").isEqualTo(events.size());

            store.seal();
            assertThat(count(store, plan)).as("after sealing").isEqualTo(events.size());
        }
    }

    private static boolean covers(List<MortonRange> ranges, long key) {
        return ranges.stream()
                .anyMatch(range ->
                        Long.compareUnsigned(range.low(), key) <= 0 && Long.compareUnsigned(key, range.high()) <= 0);
    }

    private static int count(EventStore store, ScanPlan plan) throws StoreException {
        int rows = 0;
        MutationBatch batch = new MutationBatch(plan.batchSize());
        try (MutationCursor cursor = store.scan(plan)) {
            while (cursor.next(batch)) {
                rows += batch.size();
            }
        }
        return rows;
    }
}
