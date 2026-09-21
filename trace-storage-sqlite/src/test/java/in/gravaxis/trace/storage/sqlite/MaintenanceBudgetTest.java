/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.storage.*;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceBudgetTest {
    @Test
    void sqliteVmCallbackAndDeadlineEachTakeTheirOwnStopBranch() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var control = new MaintenanceControl(new MaintenanceBudget(100, 8, 5000, () -> calls.incrementAndGet() > 1));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
                var statement = connection.createStatement()) {
            control.install(connection);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> statement.executeQuery(
                                    "WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT sum(x) FROM n"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThat(calls.get()).isGreaterThan(1);
            assertThat(control.stopped).isEqualTo(MaintenanceResult.State.CANCELLED);
            org.sqlite.ProgressHandler.clearHandler(connection);
            assertThat(statement.execute("SELECT 1")).isTrue();
        }
        var deadline = new MaintenanceControl(new MaintenanceBudget(100, 8, 1, () -> false));
        Thread.sleep(5);
        org.assertj.core.api.Assertions.assertThatThrownBy(deadline::check).isInstanceOf(java.sql.SQLException.class);
        assertThat(deadline.stopped).isEqualTo(MaintenanceResult.State.DEFERRED);
    }

    @TempDir
    Path directory;

    private static final long T = 1_800_000_000_000L;

    private void seed(SqliteEventStore store) throws Exception {
        for (int i = 0; i < 4; i++) {
            store.append(Events.batchOf(List.of(new Events(1, i, 64, 0, T + i, i, 1, 2, 16, Cause.BREAKING))), i);
            assertThat(store.seal()).isEqualTo(1);
        }
    }

    @Test
    void admitsSubsetDefersOversizeAndCancelsAfterOutputWithoutPublishing() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            assertThat(store.compact(new MaintenanceBudget(1, 2, 5000, () -> false))
                            .state())
                    .isEqualTo(MaintenanceResult.State.DEFERRED);
            assertThat(store.compact(new MaintenanceBudget(2, 2, 5000, () -> false))
                            .rows())
                    .isEqualTo(2);
            assertThat(store.stats().shardCount()).isEqualTo(3);
            AtomicBoolean cancel = new AtomicBoolean();
            store.maintenanceProbe(phase -> {
                if (phase.equals("rewrite.output-forced")) cancel.set(true);
            });
            assertThat(store.expireBefore(T + 2, new MaintenanceBudget(100, 8, 5000, cancel::get))
                            .state())
                    .isEqualTo(MaintenanceResult.State.CANCELLED);
            assertThat(cancel).isTrue();
            assertThat(store.stats().sealedRows()).isEqualTo(4);
            assertThat(store.gapsBetween(T, T + 4)).isEmpty();
            try (var files = java.nio.file.Files.list(directory.resolve("shards"))) {
                assertThat(files.count())
                        .as("cancelled output must not accumulate until restart")
                        .isEqualTo(3);
            }
            store.maintenanceProbe(phase -> {});
            assertThat(store.compact()).isEqualTo(4);
            assertThat(store.verify().verified()).isEqualTo(1);
        }
    }

    @Test
    void cleanupIsBoundedAndContinuesWhenCompactionHasNoWork() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            var budget = new MaintenanceBudget(100, 2, 5000, () -> false);
            var plan = ScanPlan.of(
                    1,
                    new in.gravaxis.trace.core.geom.BlockBox(0, 0, 0, 10, 100, 10),
                    T,
                    T + 10,
                    ScanPlan.Order.OLDEST_FIRST);
            try (var reader = store.scan(plan)) {
                assertThat(reader.next(new MutationBatch(1))).isTrue();
                for (int i = 0; i < 3; i++)
                    assertThat(store.compact(budget).state()).isEqualTo(MaintenanceResult.State.COMPLETED);
                assertThat(shardFiles()).isEqualTo(7);
            }
            for (long remaining : new long[] {5, 3, 1}) {
                assertThat(store.compact(budget).state()).isEqualTo(MaintenanceResult.State.NO_WORK);
                assertThat(shardFiles()).isEqualTo(remaining);
            }
            assertThat(store.stats().sealedRows()).isEqualTo(4);
        }
    }

    private long shardFiles() throws Exception {
        try (var files = java.nio.file.Files.list(directory.resolve("shards"))) {
            return files.count();
        }
    }

    @Test
    void cooperativeCancellationInterruptsWorkAndCleanupAllowsNextPass() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var result = store.compact(new MaintenanceBudget(100, 8, 5000, () -> calls.incrementAndGet() > 12));
            assertThat(result.state()).isEqualTo(MaintenanceResult.State.CANCELLED);
            assertThat(calls.get()).isGreaterThan(12);
            assertThat(store.stats().shardCount()).isEqualTo(4);
            assertThat(store.compact()).isEqualTo(4);
        }
    }

    @Test
    void scheduleAssertsDisabledBusyCompletionAndRetentionOptIn() throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            seed(store);
            var disabled =
                    new MaintenanceSchedule(store, new MaintenancePolicy(false, 1, 100, 8, 5000, 0), () -> false, T);
            disabled.tick(T + 1, false);
            assertThat(disabled.completed()).isZero();
            assertThat(store.stats().shardCount()).isEqualTo(4);
            var active =
                    new MaintenanceSchedule(store, new MaintenancePolicy(true, 1, 100, 8, 5000, 0), () -> false, T);
            active.tick(T + 1, true);
            assertThat(active.deferred()).isEqualTo(1);
            active.tick(T + 2, false);
            assertThat(active.completed()).isEqualTo(1);
            active.tick(T + 3, false);
            assertThat(store.stats().sealedRows()).isEqualTo(4);
            assertThat(store.gapsBetween(T, T + 4)).isEmpty();
            var retention =
                    new MaintenanceSchedule(store, new MaintenancePolicy(true, 1, 100, 8, 5000, 1), () -> false, T);
            retention.tick(T + 8, false);
            retention.tick(T + 9, false);
            assertThat(store.gapsBetween(T, T + 4)).isEmpty();
            for (int i = 10; i < 40; i++) retention.tick(T + i, false);
            assertThat(retention.completed()).isPositive();
            assertThat(store.stats().sealedRows()).isZero();
            assertThat(store.gapsBetween(T, T + 4)).isNotEmpty();
        }
    }
}
