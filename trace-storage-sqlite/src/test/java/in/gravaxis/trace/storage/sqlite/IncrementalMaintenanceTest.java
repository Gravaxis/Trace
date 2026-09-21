/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static in.gravaxis.trace.storage.MaintenanceOperation.*;
import static in.gravaxis.trace.storage.MaintenanceResult.State.*;
import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.storage.*;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalMaintenanceTest {
    @Test
    void completeRemovalAuditCountsRemovedRowsAndDoesNotNameAFutureShard() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            store.seal();
            assertThat(finish(store, RETAIN, ShardMaintenanceTest.T + 1).rows()).isEqualTo(1);
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 2, 16))), 1);
            store.seal();
            assertThat(store.purgeActor(16, ShardMaintenanceTest.T + 2, ShardMaintenanceTest.T + 3))
                    .isEqualTo(1);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement();
                    var rows = s.executeQuery(
                            "SELECT action,shard_id,affected FROM maintenance_audit WHERE action IN ('STEP_RETAIN','REMOVE') ORDER BY id")) {
                for (String action : new String[] {"STEP_RETAIN", "REMOVE"}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(action);
                    assertThat(rows.getLong(2))
                            .as("no replacement was published")
                            .isZero();
                    assertThat(rows.getLong(3)).as("removed, not retained rows").isEqualTo(1);
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void scheduleActuallyQuarantinesRetriesCheckpointAndSeals() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            store.seal();
            try (var c = DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("shards/shard-000000000001.db"));
                    var s = c.createStatement()) {
                s.executeUpdate("UPDATE ev SET b=999");
            }
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 10, 16))), 1);
            var schedule = new in.gravaxis.trace.storage.MaintenanceSchedule(
                    store, new in.gravaxis.trace.storage.MaintenancePolicy(true, 1, 1, 2, 5000, 0), () -> false, 0);
            schedule.tick(1, false); // No pair to compact.
            schedule.tick(2, false); // The first verification page must yield.
            assertThat(store.stats().shardCount()).isEqualTo(1);
            schedule.tick(3, false);
            assertThat(schedule.status()).contains("quarantined=1");
            assertThat(store.stats().shardCount()).isZero();
            schedule.tick(4, false); // Retention disabled.
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement()) {
                c.setAutoCommit(false);
                try (var rows = s.executeQuery("SELECT count(*) FROM shard")) {
                    assertThat(rows.next()).isTrue();
                }
                store.recordGap(new GapRecord(1, 2, GapRecord.Reason.CRASH_WINDOW, 0, "checkpoint scheduling fixture"));
                schedule.tick(5, false);
                assertThat(schedule.deferred()).isEqualTo(1);
                assertThat(Files.size(directory.resolve("manifest.db-wal"))).isPositive();
            }
            schedule.tick(6, false);
            assertThat(Files.size(directory.resolve("manifest.db-wal"))).isZero();
            for (int i = 7; i <= 10; i++) schedule.tick(i, false);
            assertThat(store.stats().hotRows()).isZero();
            assertThat(store.stats().sealedRows()).isEqualTo(1);
            assertThat(store.verify().verified()).isEqualTo(1);
        }
    }

    @TempDir
    Path directory;

    private static final MaintenanceBudget ONE = new MaintenanceBudget(1, 2, 5000, () -> false);

    private static void seed(SqliteEventStore store) throws Exception {
        for (int i = 0; i < 2; i++) {
            store.append(
                    Events.batchOf(List.of(
                            ShardMaintenanceTest.event(i, ShardMaintenanceTest.T + i, 16),
                            ShardMaintenanceTest.event(i + 10, ShardMaintenanceTest.T + i + 10, 16))),
                    i);
            store.seal();
        }
    }

    private static MaintenanceResult finish(SqliteEventStore store, MaintenanceOperation op, long cutoff)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            var result = store.maintain(op, ONE, cutoff);
            if (result.state() != PROGRESSED) return result;
        }
        throw new AssertionError("Incremental work failed to finish");
    }

    @Test
    void oversizedMergeRetainsProgressAndAllowsAppendBetweenSteps() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            var first = store.maintain(COMPACT, ONE, 0);
            assertThat(first.state()).isEqualTo(PROGRESSED);
            assertThat(first.rows()).isEqualTo(1);
            assertThat(store.stats().shardCount()).isEqualTo(2);
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(30, ShardMaintenanceTest.T + 30, 16))), 3);
            var result = finish(store, COMPACT, 0);
            assertThat(result.state()).isEqualTo(COMPLETED);
            assertThat(result.rows()).isEqualTo(4);
            assertThat(store.stats().hotRows()).isEqualTo(1);
            assertThat(store.stats().sealedRows()).isEqualTo(4);
            assertThat(store.verify().verified()).isEqualTo(1);
        }
    }

    @Test
    void incrementalSealPublishesOnlyFrozenPrefix() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            assertThat(store.maintain(SEAL, ONE, 0).state()).isEqualTo(PROGRESSED);
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 1, 16))), 1);
            assertThat(finish(store, SEAL, 0).rows()).isEqualTo(1);
            assertThat(store.stats().hotRows()).isEqualTo(1);
            assertThat(store.verify().verified()).isEqualTo(1);
            assertThat(finish(store, SEAL, 0).state()).isEqualTo(COMPLETED);
            assertThat(store.stats().hotRows()).isZero();
            assertThat(store.stats().sealedRows()).isEqualTo(2);
        }
    }

    @Test
    void cancelAndDestructiveMutationCannotPublishStaleRows() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            assertThat(store.maintain(COMPACT, ONE, 0).state()).isEqualTo(PROGRESSED);
            assertThat(store.maintain(COMPACT, new MaintenanceBudget(1, 2, 5000, () -> true), 0)
                            .state())
                    .isEqualTo(CANCELLED);
            try (var files = Files.list(directory.resolve("shards"))) {
                assertThat(files.count()).isEqualTo(2);
            }
            assertThat(store.maintain(COMPACT, ONE, 0).state()).isEqualTo(PROGRESSED);
            assertThat(store.purgeActor(16, ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100))
                    .isEqualTo(4);
            assertThat(finish(store, COMPACT, 0).state()).isEqualTo(NO_WORK);
            assertThat(store.stats().sealedRows()).isZero();
        }
    }

    @Test
    void scheduledVerifyDistinguishesCancellationHealthyAndCorruptInput() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            assertThat(store.maintain(VERIFY, ONE, 0).state()).isEqualTo(PROGRESSED);
            assertThat(store.maintain(VERIFY, new MaintenanceBudget(1, 2, 5000, () -> true), 0)
                            .state())
                    .isEqualTo(CANCELLED);
            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100))
                    .isEmpty();
            assertThat(finish(store, VERIFY, 0).state()).isEqualTo(COMPLETED);
            try (var c = DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("shards/shard-000000000002.db"));
                    var s = c.createStatement()) {
                s.executeUpdate("UPDATE ev SET b=999");
            }
            assertThat(finish(store, VERIFY, 0).state()).isEqualTo(QUARANTINED);
            assertThat(store.stats().shardCount()).isEqualTo(1);
            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100))
                    .singleElement()
                    .satisfies(gap -> assertThat(gap.reason()).isEqualTo(GapRecord.Reason.QUARANTINE));
        }
    }

    @Test
    void checkpointDefersForSnapshotThenActuallyTruncates() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement()) {
                c.setAutoCommit(false);
                try (var r = s.executeQuery("SELECT count(*) FROM shard")) {
                    assertThat(r.next()).isTrue();
                }
                store.recordGap(new GapRecord(1, 2, GapRecord.Reason.CRASH_WINDOW, 0, "checkpoint test"));
                assertThat(Files.size(directory.resolve("manifest.db-wal"))).isPositive();
                assertThat(store.maintain(CHECKPOINT, ONE, 0).state()).isEqualTo(DEFERRED);
            }
            assertThat(store.maintain(CHECKPOINT, ONE, 0).state()).isEqualTo(COMPLETED);
            assertThat(Files.size(directory.resolve("manifest.db-wal"))).isZero();
        }
    }

    @Test
    void outputBoundaryCancellationDoesNotPublishAndReaderPinsRetiredFiles() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            var cancel = new java.util.concurrent.atomic.AtomicBoolean();
            store.maintenanceProbe(phase -> {
                if (phase.equals("incremental.output-forced")) cancel.set(true);
            });
            var result = store.maintain(COMPACT, new MaintenanceBudget(100, 2, 5000, cancel::get), 0);
            assertThat(cancel).isTrue();
            assertThat(result.state()).isEqualTo(CANCELLED);
            assertThat(store.stats().shardCount()).isEqualTo(2);
            store.maintenanceProbe(phase -> {});
            try (var cursor = store.scan(ShardMaintenanceTest.PLAN)) {
                var batch = new MutationBatch(1);
                assertThat(cursor.next(batch)).isTrue();
                assertThat(finish(store, COMPACT, 0).state()).isEqualTo(COMPLETED);
                try (var files = Files.list(directory.resolve("shards"))) {
                    assertThat(files.count()).isEqualTo(3);
                }
                int remaining = 0;
                while (cursor.next(batch)) remaining++;
                assertThat(remaining).isEqualTo(3);
            }
            assertThat(finish(store, COMPACT, 0).state()).isEqualTo(NO_WORK);
            try (var files = Files.list(directory.resolve("shards"))) {
                assertThat(files.count()).isEqualTo(1);
            }
        }
    }

    @Test
    void retentionYieldsPreservesCutoffAndPreventsReplay() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            var events = List.of(
                    ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16),
                    ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 1, 16));
            store.append(Events.batchOf(events), 0);
            store.seal();
            String blob = store.putBlob(1, new byte[] {1, 2, 3});
            var before = new in.gravaxis.trace.storage.CursorPosition(
                    in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T, 1);
            var at = new in.gravaxis.trace.storage.CursorPosition(
                    in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T + 1, 2);
            store.attachBlob(1, before, blob);
            store.attachBlob(1, at, blob);
            try (var reader = store.scan(ShardMaintenanceTest.PLAN)) {
                assertThat(reader.next(new MutationBatch(1))).isTrue();
                assertThat(store.maintain(RETAIN, ONE, ShardMaintenanceTest.T + 1)
                                .state())
                        .isEqualTo(DEFERRED);
            }
            assertThat(store.maintain(RETAIN, ONE, ShardMaintenanceTest.T + 1).state())
                    .isEqualTo(PROGRESSED);
            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 2))
                    .isEmpty();
            var result = finish(store, RETAIN, ShardMaintenanceTest.T + 1);
            assertThat(result.state()).isEqualTo(COMPLETED);
            assertThat(result.rows()).isEqualTo(1);
            assertThat(store.blobAt(1, before)).isNull();
            assertThat(store.blobAt(1, at)).isEqualTo(blob);
            assertThat(store.collectBlobs()).isZero();
            assertThat(store.readBlob(blob)).containsExactly(1, 2, 3);
            assertThat(store.stats().sealedRows()).isEqualTo(1);
            var after = ScanPlan.of(
                    1,
                    ShardMaintenanceTest.PLAN.box(),
                    ShardMaintenanceTest.T + 1,
                    ShardMaintenanceTest.T + 2,
                    ScanPlan.Order.OLDEST_FIRST);
            try (var reader = store.scan(after)) {
                var batch = new MutationBatch(1);
                assertThat(reader.next(batch)).isTrue();
                assertThat(batch.timestamp(0)).isEqualTo(ShardMaintenanceTest.T + 1);
                assertThat(reader.next(batch)).isFalse();
            }
            store.append(Events.batchOf(events.subList(0, 1)), 1);
            assertThat(store.stats().hotRows()).isZero();
            assertThat(store.verify().verified()).isEqualTo(1);
        }
    }
}
