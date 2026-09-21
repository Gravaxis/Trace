/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShardMaintenanceTest {
    @Test
    void generatedMultisetAndBothDirectionsSurviveCompaction() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            java.util.SplittableRandom random = new java.util.SplittableRandom(20260921);
            for (int shard = 0; shard < 7; shard++) {
                List<Events> events = new ArrayList<>();
                for (int row = 0; row < 37; row++)
                    events.add(new Events(
                            1,
                            random.nextInt(-90, 90),
                            random.nextInt(0, 90),
                            random.nextInt(-90, 90),
                            T + shard * 100 + row,
                            row,
                            random.nextInt(256),
                            random.nextInt(256),
                            16 + row % 5,
                            Cause.BREAKING));
                store.append(Events.batchOf(events), shard);
                store.seal();
            }
            List<String> expected = readAllFields(store, PLAN);
            ScanPlan reverse = ScanPlan.of(1, PLAN.box(), T, T + 1000, ScanPlan.Order.NEWEST_FIRST)
                    .withBatchSize(3);
            List<String> reversed = readAllFields(store, reverse);
            assertThat(store.compact()).isEqualTo(7 * 37);
            assertThat(readAllFields(store, PLAN)).containsExactlyElementsOf(expected);
            assertThat(readAllFields(store, reverse)).containsExactlyElementsOf(reversed);
            assertThat(store.verify().verified()).isEqualTo(1);
        }
    }

    private static List<String> readAllFields(SqliteEventStore store, ScanPlan plan) throws Exception {
        List<String> result = new ArrayList<>();
        try (var cursor = store.scan(plan)) {
            var b = new MutationBatch(1);
            while (cursor.next(b))
                result.add(b.chunkKey(0) + ":" + b.timestamp(0) + ":" + b.sequence(0) + ":" + b.x(0) + ":" + b.y(0)
                        + ":" + b.z(0) + ":" + b.beforeState(0) + ":" + b.afterState(0) + ":" + b.actorId(0) + ":"
                        + b.cause(0) + ":" + b.kind(0));
        }
        return result;
    }

    @Test
    void quarantineRollsBackStateWhenGapInsertionFails() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16))), 0);
            store.seal();
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement()) {
                s.execute("CREATE TRIGGER fail_gap BEFORE INSERT ON gap BEGIN SELECT RAISE(ABORT,'injected'); END");
            }
            assertThatThrownBy(() -> store.quarantineShard(1, "test")).isInstanceOf(StoreException.class);
            assertThat(store.stats().shardCount()).isEqualTo(1);
            assertThat(store.gapsBetween(T, T + 1)).isEmpty();
            assertThat(read(store)).hasSize(1);
        }
    }

    @Test
    void duplicateIdentityCannotDisappearDuringMerge() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            var batch = Events.batchOf(List.of(event(1, T, 16)));
            store.append(batch, 0);
            store.seal();
            store.append(batch, 1);
            store.seal();
            assertThatThrownBy(store::compact).isInstanceOf(StoreException.class);
            assertThat(store.stats().sealedRows()).isEqualTo(2);
            assertThat(store.stats().shardCount()).isEqualTo(2);
        }
    }

    @Test
    void pagedHotSnapshotSurvivesSealWithoutMissingOrDuplicatingRows() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16), event(2, T + 1, 16))), 0);
            try (var cursor = store.scan(PLAN)) {
                var batch = new MutationBatch(1);
                assertThat(cursor.next(batch)).isTrue();
                assertThat(batch.x(0)).isEqualTo(1);
                store.seal();
                assertThat(cursor.next(batch)).isTrue();
                assertThat(batch.x(0)).isEqualTo(2);
                assertThat(cursor.next(batch)).isFalse();
            }
        }
    }

    @TempDir
    Path directory;

    static final long T = 1_800_000_000_000L;
    static final ScanPlan PLAN = ScanPlan.of(
                    1, new BlockBox(-100, -100, -100, 100, 100, 100), T, T + 1000, ScanPlan.Order.OLDEST_FIRST)
            .withBatchSize(1);

    static Events event(int x, long time, int actor) {
        return new Events(1, x, 64, 0, time, x & 65535, 1, 2, actor, Cause.BREAKING);
    }

    @Test
    void mergePreservesRowsAndPinnedCursorAndStoredResume() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16))), 0);
            store.seal();
            store.append(Events.batchOf(List.of(event(2, T + 100, 17))), 1);
            store.seal();
            var before = read(store);
            try (var cursor = store.scan(PLAN)) {
                var batch = new MutationBatch(1);
                assertThat(cursor.next(batch)).isTrue();
                var position = cursor.position();
                assertThat(store.compact()).isEqualTo(2);
                assertThat(store.stats().shardCount()).isEqualTo(1);
                assertThat(cursor.next(batch)).isTrue();
                assertThat(batch.x(0)).isEqualTo(2);
                try (var resumed = store.scan(PLAN.resumeAfter(position))) {
                    assertThat(resumed.next(batch)).isTrue();
                    assertThat(batch.x(0)).isEqualTo(2);
                    assertThat(resumed.next(batch)).isFalse();
                }
                assertThat(read(store)).isEqualTo(before);
                try (var files = Files.list(directory.resolve("shards"))) {
                    assertThat(files.count()).isEqualTo(3);
                }
            }
            store.sweepRetired();
            try (var files = Files.list(directory.resolve("shards"))) {
                assertThat(files.count()).isEqualTo(1);
            }
        }
    }

    @Test
    void changedPayloadQuarantinesEvenWhenSqliteStructureIsHealthy() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16))), 0);
            store.seal();
            Path shard;
            try (var files = Files.list(directory.resolve("shards"))) {
                shard = files.findFirst().orElseThrow();
            }
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + shard);
                    var s = c.createStatement()) {
                s.execute("UPDATE ev SET b=999");
                try (var r = s.executeQuery("PRAGMA integrity_check")) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getString(1)).isEqualTo("ok");
                }
            }
            var report = store.verify();
            assertThat(report.quarantined()).isEqualTo(1);
            assertThat(report.verified()).isZero();
            assertThat(store.stats().shardCount()).isZero();
            assertThat(store.gapsBetween(T, T + 1)).hasSize(1);
            assertThatThrownBy(() -> store.scan(PLAN)).isInstanceOf(StoreException.class);
            assertThat(store.verify().quarantined()).isZero();
            assertThat(shard).exists();
        }
    }

    @Test
    void healthyAndMissingBranchesAreDistinct() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16))), 0);
            store.seal();
            assertThat(store.verify().verified()).isEqualTo(1);
            try (var files = Files.list(directory.resolve("shards"))) {
                Files.delete(files.findFirst().orElseThrow());
            }
            assertThat(store.verify().quarantined()).isEqualTo(1);
            try (var files = Files.list(directory.resolve("shards"))) {
                assertThat(files.count()).isZero();
            }
        }
    }

    @Test
    void purgeFiltersHotAndSealedAndCannotBeReplayed() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            var batch = Events.batchOf(List.of(event(1, T, 16), event(2, T + 1, 17)));
            store.append(batch, 0);
            store.seal();
            store.append(Events.batchOf(List.of(event(3, T + 2, 16), event(4, T + 3, 17))), 1);
            assertThat(store.purgeActor(16, T, T + 100)).isEqualTo(2);
            assertThat(store.stats().hotRows() + store.stats().sealedRows()).isEqualTo(2);
            store.append(Events.batchOf(List.of(event(5, T + 4, 16))), 2);
            assertThat(store.stats().hotRows() + store.stats().sealedRows()).isEqualTo(2);
            assertThat(store.purgeActor(16, T, T + 100)).isZero();
            assertThat(store.gapsBetween(T, T + 100)).isNotEmpty();
        }
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(6, T + 5, 16))), 3);
            assertThat(store.stats().hotRows() + store.stats().sealedRows()).isEqualTo(2);
        }
    }

    @Test
    void retentionKeepsCutoffAndDefersWhileReaderIsOpen() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(event(1, T, 16), event(2, T + 1, 16))), 0);
            store.seal();
            try (var cursor = store.scan(PLAN)) {
                assertThatThrownBy(() -> store.expireBefore(T + 1))
                        .isInstanceOfSatisfying(
                                StoreException.class,
                                e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CONTENDED));
                assertThat(cursor.next(new MutationBatch(1))).isTrue();
            }
            assertThat(store.expireBefore(T + 1)).isEqualTo(1);
            assertThat(store.stats().sealedRows()).isEqualTo(1);
            assertThat(store.expireBefore(T + 1)).isZero();
            assertThat(store.gapsBetween(T, T)).isNotEmpty();
        }
    }

    static List<String> read(SqliteEventStore store) throws Exception {
        List<String> result = new ArrayList<>();
        try (var cursor = store.scan(PLAN)) {
            MutationBatch batch = new MutationBatch(1);
            while (cursor.next(batch))
                result.add(batch.x(0) + ":" + batch.timestamp(0) + ":" + batch.beforeState(0) + ":" + batch.actorId(0));
        }
        return result;
    }
}
