/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.testing;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.core.testing.Gen;
import in.gravaxis.trace.core.testing.Properties;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What every storage backend has to do, expressed once.
 *
 * <p>The build spec requires the same suite to run against each tier — the embedded default today,
 * external tiers later — because a backend that quietly differs is a rollback that quietly differs.
 * A backend that cannot pass this does not ship.
 *
 * <p>The properties here are the ones a rollback depends on: rows come back, in the right order,
 * exactly once, whether they are in the hot window or sealed, and a gap is never lost.
 */
public abstract class EventStoreContract {

    private static final int WORLD = 1;
    private static final long T0 = 1_800_000_000_000L;

    @TempDir
    protected Path directory;

    private EventStore store;

    /** Opens a store in {@code directory}. Called again by tests that reopen. */
    protected abstract EventStore open(Path directory) throws StoreException;

    @BeforeEach
    void openStore() throws StoreException {
        store = open(directory);
    }

    @AfterEach
    void closeStore() throws StoreException {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("rows written are readable before anything is sealed")
    void readsBeforeSealing() throws StoreException {
        List<Events> events = line(5, T0);
        append(events, 1);

        assertThat(scanAll(planFor(events))).containsExactlyElementsOf(oracle(events, planFor(events)));
    }

    @Test
    @DisplayName("rows written are readable after sealing, and the hot window is emptied")
    void readsAfterSealing() throws StoreException {
        List<Events> events = line(5, T0);
        append(events, 1);

        long sealed = store.seal();

        assertThat(sealed).isEqualTo(events.size());
        assertThat(store.stats().hotRows()).isZero();
        assertThat(store.stats().sealedRows()).isEqualTo(events.size());
        assertThat(scanAll(planFor(events))).containsExactlyElementsOf(oracle(events, planFor(events)));
    }

    @Test
    @DisplayName("rows in the hot window and rows in shards come back as one ordered stream")
    void mergesHotAndSealed() throws StoreException {
        List<Events> older = line(4, T0);
        append(older, 1);
        store.seal();
        List<Events> newer = line(4, T0 + 10);
        append(newer, 2);

        List<Events> all = new ArrayList<>(older);
        all.addAll(newer);
        ScanPlan plan = planFor(all);

        assertThat(scanAll(plan)).containsExactlyElementsOf(oracle(all, plan));
    }

    @Test
    @DisplayName("sealing twice is not an error and does not duplicate rows")
    void sealingIsIdempotent() throws StoreException {
        List<Events> events = line(3, T0);
        append(events, 1);

        store.seal();
        long second = store.seal();

        assertThat(second).isZero();
        assertThat(scanAll(planFor(events))).hasSize(events.size());
    }

    @Test
    @DisplayName("replaying an already-applied batch changes nothing")
    void replayIsIdempotent() throws StoreException {
        List<Events> events = line(3, T0);
        append(events, 7);

        // Recovery re-sends whatever it cannot prove landed. Applying it twice must not double it.
        append(events, 7);
        append(events, 3);

        assertThat(scanAll(planFor(events))).hasSize(events.size());
        assertThat(store.appliedLsn()).isEqualTo(7);
    }

    @Test
    @DisplayName("the scan order is exactly the one the plan asked for")
    void ordersAsAsked() throws StoreException {
        List<Events> events = line(6, T0);
        append(events, 1);

        List<Row> newest = scanAll(planFor(events, ScanPlan.Order.NEWEST_FIRST));
        List<Row> oldest = scanAll(planFor(events, ScanPlan.Order.OLDEST_FIRST));

        assertThat(newest).containsExactlyElementsOf(oracle(events, planFor(events, ScanPlan.Order.NEWEST_FIRST)));
        assertThat(oldest).containsExactlyElementsOf(oracle(events, planFor(events, ScanPlan.Order.OLDEST_FIRST)));
        assertThat(newest).isNotEqualTo(oldest);
    }

    @Test
    @DisplayName("rows outside the box are filtered even when the key cover is coarse")
    void filtersOutsideTheBox() throws StoreException {
        List<Events> events = new ArrayList<>();
        events.add(event(0, 64, 0, T0, 0));
        events.add(event(100, 64, 100, T0, 1));
        append(events, 1);

        // A box around the first event only. The Morton cover may well include the other chunk.
        ScanPlan plan = ScanPlan.of(
                WORLD, new BlockBox(-1, 60, -1, 1, 70, 1), T0 - 1000, T0 + 1000, ScanPlan.Order.NEWEST_FIRST);

        assertThat(scanAll(plan))
                .containsExactlyElementsOf(oracle(events, plan))
                .hasSize(1);
    }

    @Test
    @DisplayName("a scan visits every matching row exactly once, at any page size")
    void pagingVisitsEveryRowOnce() {
        java.util.concurrent.atomic.AtomicInteger caseNumber = new java.util.concurrent.atomic.AtomicInteger();
        Properties.forAll("paging visits every row once", 25, gen -> {
            // Not drawn from the generator: shrinking would change it, and replays would then share
            // a directory with each other's rows.
            try (EventStore fresh = open(directory.resolve("case-" + caseNumber.incrementAndGet()))) {
                List<Events> events = randomEvents(gen, gen.ints(1, 120));
                // Written in several batches, with a seal part-way, so the scan has to merge.
                int split = gen.ints(0, events.size());
                append(fresh, events.subList(0, split), 1);
                if (gen.bools()) {
                    fresh.seal();
                }
                append(fresh, events.subList(split, events.size()), 2);

                ScanPlan plan = planFor(events, gen.bools() ? ScanPlan.Order.NEWEST_FIRST : ScanPlan.Order.OLDEST_FIRST)
                        .withBatchSize(gen.ints(1, 16));

                assertThat(scanAll(fresh, plan)).containsExactlyElementsOf(oracle(events, plan));
            } catch (StoreException e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("a cursor reports a position that matches the last row it returned")
    void reportsItsPosition() throws StoreException {
        List<Events> events = line(5, T0);
        append(events, 1);
        ScanPlan plan = planFor(events).withBatchSize(2);

        try (MutationCursor cursor = store.scan(plan)) {
            assertThat(cursor.position())
                    .as("no position before the first page")
                    .isNull();
            MutationBatch batch = new MutationBatch(plan.batchSize());
            assertThat(cursor.next(batch)).isTrue();
            CursorPosition position = requireNonNull(cursor.position(), "a page was returned but no position");
            assertThat(position.timestamp()).isEqualTo(batch.timestamp(batch.size() - 1));
            assertThat(position.sequence()).isEqualTo(batch.sequence(batch.size() - 1));
        }
    }

    @Test
    @DisplayName("gaps are recorded and found by the window they overlap")
    void recordsGaps() throws StoreException {
        store.recordGap(new GapRecord(T0, T0 + 100, GapRecord.Reason.OVERFLOW, 42, "ring 3 overflowed"));

        assertThat(store.gapsBetween(T0 + 50, T0 + 500)).hasSize(1);
        assertThat(store.gapsBetween(T0 - 500, T0 - 1)).isEmpty();
        assertThat(store.gapsBetween(T0 + 200, T0 + 500)).isEmpty();
        assertThat(store.gapsBetween(T0, T0).get(0).droppedCount()).isEqualTo(42);
        assertThat(store.stats().gapCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rows survive closing and reopening the store")
    void survivesReopening() throws StoreException {
        List<Events> events = line(4, T0);
        append(events, 5);
        store.seal();
        append(line(2, T0 + 50), 6);
        store.close();

        store = open(directory);

        assertThat(store.appliedLsn()).isEqualTo(6);
        assertThat(scanAll(planFor(events))).hasSize(4);
    }

    @Test
    @DisplayName("a second server opening the same data directory is refused, by name")
    void refusesASecondWriter() {
        assertThatThrownBy(() -> open(directory))
                .isInstanceOf(StoreException.class)
                .satisfies(e -> assertThat(((StoreException) e).reason()).isEqualTo(StoreException.Reason.LOCKED))
                .hasMessageContaining("data directory");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void append(List<Events> events, long lsn) throws StoreException {
        append(store, events, lsn);
    }

    private static void append(EventStore target, List<Events> events, long lsn) throws StoreException {
        if (events.isEmpty()) {
            return;
        }
        RecordBatch batch = Events.batchOf(events);
        target.append(batch, lsn);
    }

    private List<Row> scanAll(ScanPlan plan) throws StoreException {
        return scanAll(store, plan);
    }

    private static List<Row> scanAll(EventStore target, ScanPlan plan) throws StoreException {
        List<Row> rows = new ArrayList<>();
        MutationBatch batch = new MutationBatch(plan.batchSize());
        try (MutationCursor cursor = target.scan(plan)) {
            while (cursor.next(batch)) {
                for (int i = 0; i < batch.size(); i++) {
                    rows.add(new Row(
                            batch.x(i),
                            batch.y(i),
                            batch.z(i),
                            batch.timestamp(i),
                            batch.sequence(i),
                            batch.beforeState(i),
                            batch.afterState(i),
                            batch.actorId(i)));
                }
            }
        }
        return rows;
    }

    /**
     * What the scan should have returned, worked out independently.
     *
     * <p>Brute force on purpose: filter, then sort by the key order the plan asks for. If this ever
     * starts sharing logic with the store, it stops being an oracle.
     */
    private static List<Row> oracle(List<Events> events, ScanPlan plan) {
        Comparator<Events> byKey = Comparator.comparingLong(
                        (Events e) -> in.gravaxis.trace.core.geom.Morton.key(e.x() >> 4, e.z() >> 4))
                .thenComparingLong(Events::timestamp)
                .thenComparingInt(Events::sequence);
        if (plan.order() == ScanPlan.Order.NEWEST_FIRST) {
            byKey = byKey.reversed();
        }
        return events.stream()
                .filter(e -> e.worldId() == plan.worldId())
                .filter(e -> e.timestamp() >= plan.fromMillis() && e.timestamp() < plan.toMillis())
                .filter(e -> plan.box().contains(e.x(), e.y(), e.z()))
                .filter(e -> plan.acceptsActor(e.actorId()))
                .sorted(byKey)
                .map(e -> new Row(
                        e.x(), e.y(), e.z(), e.timestamp(), e.sequence(), e.beforeState(), e.afterState(), e.actorId()))
                .toList();
    }

    private static ScanPlan planFor(List<Events> events) {
        return planFor(events, ScanPlan.Order.NEWEST_FIRST);
    }

    private static ScanPlan planFor(List<Events> events, ScanPlan.Order order) {
        int minX = events.stream().mapToInt(Events::x).min().orElse(0);
        int maxX = events.stream().mapToInt(Events::x).max().orElse(0);
        int minZ = events.stream().mapToInt(Events::z).min().orElse(0);
        int maxZ = events.stream().mapToInt(Events::z).max().orElse(0);
        int minY = events.stream().mapToInt(Events::y).min().orElse(0);
        int maxY = events.stream().mapToInt(Events::y).max().orElse(0);
        long from = events.stream().mapToLong(Events::timestamp).min().orElse(T0);
        long to = events.stream().mapToLong(Events::timestamp).max().orElse(T0) + 1;
        return ScanPlan.of(WORLD, new BlockBox(minX, minY, minZ, maxX, maxY, maxZ), from, to, order);
    }

    private static List<Events> line(int count, long startMillis) {
        List<Events> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(event(i, 64, i * 3, startMillis + i, i));
        }
        return events;
    }

    private static Events event(int x, int y, int z, long timestamp, int sequence) {
        return new Events(WORLD, x, y, z, timestamp, sequence, 10 + sequence, 0, 7, Cause.BREAKING);
    }

    private static List<Events> randomEvents(Gen gen, int count) {
        List<Events> events = new ArrayList<>(count);
        long base = T0 + gen.longs(0, 10_000);
        for (int i = 0; i < count; i++) {
            events.add(new Events(
                    WORLD,
                    gen.ints(-40, 40),
                    gen.ints(-60, 200),
                    gen.ints(-40, 40),
                    base + gen.longs(0, 50),
                    i,
                    gen.ints(0, 500),
                    gen.ints(0, 500),
                    gen.ints(1, 4),
                    Cause.BREAKING));
        }
        return events;
    }

    /** One row as a test sees it. */
    private record Row(
            int x, int y, int z, long timestamp, int sequence, int beforeState, int afterState, int actorId) {}
}
