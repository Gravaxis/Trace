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
import in.gravaxis.trace.storage.OperationProgress;
import in.gravaxis.trace.storage.OperationState;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.RollbackOperation;
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
    @DisplayName("the batch at journal position zero is kept")
    void keepsTheFirstJournalPosition() throws StoreException {
        // A journal's first frame sits at position zero. A store that reports "nothing applied" as
        // zero and then discards anything "at or below" it eats that frame, and the loss is
        // invisible: the counters say written, the scan says missing. An integration test caught
        // exactly this, five blocks at a time, so it is pinned here.
        assertThat(store.appliedLsn()).isNegative();

        List<Events> events = line(3, T0);
        append(events, 0);

        assertThat(scanAll(planFor(events))).hasSize(events.size());
        assertThat(store.appliedLsn()).isZero();

        // And it is still idempotent at zero: re-applying the same frame must not double it.
        append(events, 0);
        assertThat(scanAll(planFor(events))).hasSize(events.size());
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
    @DisplayName("resuming a scan returns exactly the rows that were left")
    void resumingReturnsExactlyTheRemainder() throws StoreException {
        List<Events> events = line(20, T0);
        append(events, 11);
        store.seal();

        ScanPlan plan = planFor(events);
        List<Row> all = scanAll(plan);
        assertThat(all).hasSize(20);

        // Read the first seven, remember where that left the cursor, then resume from it.
        CursorPosition mark;
        List<Row> firstPart = new ArrayList<>();
        MutationBatch batch = new MutationBatch(7);
        try (MutationCursor cursor = store.scan(plan.withBatchSize(7))) {
            assertThat(cursor.next(batch)).isTrue();
            for (int i = 0; i < batch.size(); i++) {
                firstPart.add(new Row(
                        batch.x(i),
                        batch.y(i),
                        batch.z(i),
                        batch.timestamp(i),
                        batch.sequence(i),
                        batch.beforeState(i),
                        batch.afterState(i),
                        batch.actorId(i),
                        batch.cause(i),
                        batch.kind(i)));
            }
            mark = cursor.position();
        }
        assertThat(firstPart).hasSize(7);

        List<Row> remainder = scanAll(plan.resumeAfter(mark));

        assertThat(firstPart).containsExactlyElementsOf(all.subList(0, 7));
        assertThat(remainder).containsExactlyElementsOf(all.subList(7, 20));
    }

    @Test
    @DisplayName("a rollback that was interrupted is still there after a restart, with its cursor")
    void remembersAnUnfinishedRollback() throws StoreException {
        List<Events> events = line(4, T0);
        append(events, 12);

        BlockBox box = BlockBox.around(0, 64, 0, 32);
        RollbackOperation starting = RollbackOperation.starting("run-one", WORLD, box, T0 - 1000, T0 + 1000, 9, T0);
        long id = store.beginOperation(starting);
        assertThat(id).isPositive();

        CursorPosition reached = new CursorPosition(7, T0 + 3, 2);
        store.checkpointOperation(id, reached, new OperationProgress(3, 0, 1, 4, 1));

        // The process dies here: nothing writes a final state. That is what "interrupted" is.
        store.close();
        store = open(directory);

        RollbackOperation found = requireNonNull(store.operation(id), "the operation must still be there");
        assertThat(found.state()).isEqualTo(OperationState.RUNNING);
        assertThat(found.cursor()).isEqualTo(reached);
        assertThat(found.box()).isEqualTo(box);
        assertThat(found.fromMillis()).isEqualTo(T0 - 1000);
        assertThat(found.toMillis()).isEqualTo(T0 + 1000);
        assertThat(found.progress().applied()).isEqualTo(3);
        assertThat(found.progress().mismatched()).isEqualTo(1);
        assertThat(store.unfinishedOperations())
                .extracting(RollbackOperation::id)
                .containsExactly(id);

        // It has work left, so it can be picked up. Whether something is running it right now is
        // not the store's question: one server per data directory means the run that wrote this is
        // gone, and a rollback of this run's own is tracked by the service running it.
        assertThat(found.isResumable()).isTrue();
        assertThat(found.runId()).isEqualTo("run-one");

        store.finishOperation(id, OperationState.DONE, new OperationProgress(4, 0, 1, 4, 1));
        assertThat(store.unfinishedOperations()).isEmpty();
    }

    @Test
    @DisplayName("a checkpoint with no cursor records progress without promising more was finished")
    void keepsTheCursorWhenAChunkCouldNotBeApplied() throws StoreException {
        BlockBox box = BlockBox.around(0, 64, 0, 32);
        long id = store.beginOperation(RollbackOperation.starting("run-one", WORLD, box, T0 - 1000, T0 + 1000, 9, T0));

        CursorPosition safe = new CursorPosition(2, T0 + 1, 0);
        store.checkpointOperation(id, safe, new OperationProgress(1, 0, 0, 1, 1));
        // A chunk that could not be applied: the counters move, the cursor must not, or resuming
        // would step over work that was never done.
        store.checkpointOperation(id, null, new OperationProgress(1, 0, 0, 9, 2));

        RollbackOperation found = requireNonNull(store.operation(id), "the operation must still be there");
        assertThat(found.cursor()).isEqualTo(safe);
        assertThat(found.progress().scanned()).isEqualTo(9);
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

    @Test
    void compactionPreservesEveryFieldAndStoredSuffixInBothDirections() throws Exception {
        List<Events> all = new ArrayList<>();
        for (int shard = 0; shard < 3; shard++) {
            List<Events> part = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                part.add(new Events(
                        WORLD,
                        i * 17 - 40,
                        65 + i,
                        -i,
                        T0 + shard * 100 + i,
                        i,
                        10 + i,
                        20 + shard,
                        7 + i,
                        i % 2 == 0 ? Cause.PLACING : Cause.BREAKING));
            append(part, shard);
            assertThat(store.seal()).isEqualTo(part.size());
            all.addAll(part);
        }
        var oldest = planFor(all, ScanPlan.Order.OLDEST_FIRST);
        var newest = planFor(all, ScanPlan.Order.NEWEST_FIRST);
        CursorPosition oldestMark;
        CursorPosition newestMark;
        try (var cursor = store.scan(oldest)) {
            assertThat(cursor.next(new MutationBatch(2))).isTrue();
            oldestMark = cursor.position();
        }
        try (var cursor = store.scan(newest)) {
            assertThat(cursor.next(new MutationBatch(2))).isTrue();
            newestMark = cursor.position();
        }
        assertThat(store.compact()).isEqualTo(all.size());
        assertThat(store.stats().shardCount()).isEqualTo(1);
        assertThat(scanAll(oldest)).containsExactlyElementsOf(oracle(all, oldest));
        assertThat(scanAll(newest)).containsExactlyElementsOf(oracle(all, newest));
        assertThat(scanAll(oldest.resumeAfter(oldestMark)))
                .containsExactlyElementsOf(oracle(all, oldest).subList(2, all.size()));
        assertThat(scanAll(newest.resumeAfter(newestMark)))
                .containsExactlyElementsOf(oracle(all, newest).subList(2, all.size()));
        assertThat(store.verify().quarantined()).isZero();
    }

    @Test
    void purgeCoversHotAndSealedRowsAndReplayCannotResurrectThem() throws Exception {
        List<Events> selected = line(2, T0);
        List<Events> retained = line(2, T0 + 100);
        append(selected.subList(0, 1), 0);
        store.seal();
        append(selected.subList(1, 2), 1);
        append(retained, 2);
        assertThat(store.purgeActor(7, T0, T0 + 2)).isEqualTo(2);
        assertThat(store.stats().hotRows() + store.stats().sealedRows()).isEqualTo(retained.size());
        assertThat(store.gapsBetween(T0, T0 + 1)).anySatisfy(g -> {
            assertThat(g.reason()).isEqualTo(GapRecord.Reason.PURGED);
            assertThat(g.droppedCount()).isEqualTo(2);
        });
        assertThatThrownBy(() -> store.scan(planFor(selected))).isInstanceOf(StoreException.class);
        store.close();
        store = open(directory);
        append(selected, 3);
        assertThat(store.appliedLsn()).isEqualTo(3);
        assertThat(store.stats().hotRows() + store.stats().sealedRows()).isEqualTo(retained.size());
        assertThat(scanAll(planFor(retained))).containsExactlyElementsOf(oracle(retained, planFor(retained)));
    }

    @Test
    void retentionIsStrictlyBeforeCutoffAndDefersWithAnOpenReader() throws Exception {
        List<Events> all = line(4, T0);
        append(all, 0);
        store.seal();
        try (var reader = store.scan(planFor(all))) {
            assertThat(reader.next(new MutationBatch(1))).isTrue();
            assertThatThrownBy(() -> store.expireBefore(T0 + 2)).isInstanceOf(StoreException.class);
            assertThat(store.stats().sealedRows()).isEqualTo(4);
            assertThat(store.gapsBetween(T0, T0 + 4)).isEmpty();
        }
        assertThat(store.expireBefore(T0 + 2)).isEqualTo(2);
        List<Events> remaining = all.subList(2, 4);
        assertThat(scanAll(planFor(remaining))).containsExactlyElementsOf(oracle(remaining, planFor(remaining)));
        assertThatThrownBy(() -> store.scan(planFor(all))).isInstanceOf(StoreException.class);
    }

    @Test
    void blobReferencesSurviveReopenAndMergeThenOnlyUnreferencedPayloadsCollect() throws Exception {
        List<Events> all = line(2, T0);
        for (int i = 0; i < all.size(); i++) {
            append(all.subList(i, i + 1), i);
            store.seal();
        }
        var first = new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), T0, 0);
        var second = new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), T0 + 1, 1);
        byte[] value = {0, -1, 3};
        String id = store.putBlob(1, value);
        assertThat(store.putBlob(1, value)).isEqualTo(id);
        String orphan = store.putBlob(2, value);
        assertThat(orphan).isNotEqualTo(id);
        store.attachBlob(WORLD, first, id);
        store.attachBlob(WORLD, second, id);
        assertThat(store.compact()).isEqualTo(2);
        store.close();
        store = open(directory);
        assertThat(store.readBlob(id)).containsExactly(value);
        assertThat(store.blobAt(WORLD, first)).isEqualTo(id);
        assertThat(store.blobAt(WORLD, second)).isEqualTo(id);
        assertThat(store.collectBlobs()).isEqualTo(1);
        assertThatThrownBy(() -> store.readBlob(orphan)).isInstanceOf(StoreException.class);
        assertThat(store.purgeActor(7, T0, T0 + 1)).isEqualTo(1);
        assertThat(store.blobAt(WORLD, first)).isNull();
        assertThat(store.collectBlobs()).isZero();
        assertThat(store.readBlob(id)).containsExactly(value);
        assertThat(store.purgeActor(7, T0 + 1, T0 + 2)).isEqualTo(1);
        assertThat(store.collectBlobs()).isEqualTo(1);
        assertThatThrownBy(() -> store.readBlob(id)).isInstanceOf(StoreException.class);
    }

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
                            batch.actorId(i),
                            batch.cause(i),
                            batch.kind(i)));
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
                        e.x(),
                        e.y(),
                        e.z(),
                        e.timestamp(),
                        e.sequence(),
                        e.beforeState(),
                        e.afterState(),
                        e.actorId(),
                        e.cause().id(),
                        in.gravaxis.trace.core.record.RecordKind.BLOCK.id()))
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
            int x,
            int y,
            int z,
            long timestamp,
            int sequence,
            int beforeState,
            int afterState,
            int actorId,
            int cause,
            int kind) {}
}
