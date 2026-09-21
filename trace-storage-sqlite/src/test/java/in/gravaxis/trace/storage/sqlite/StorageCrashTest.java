/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.storage.CaptureRecovery;
import in.gravaxis.trace.storage.GapRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageCrashTest {
    @TempDir
    Path directory;

    @Test
    void killedRewriteRecoversBeforeAndAfterPublication() throws Exception {
        for (String action : new String[] {"compact", "purge", "blob", "migration", "exhaustion"}) {
            String[] phases =
                    switch (action) {
                        case "blob" -> new String[] {"blob.payload", "blob.reference"};
                        case "migration" -> new String[] {"migration.copied", "migration.committed"};
                        case "exhaustion" -> new String[] {"exhaustion.rejected"};
                        default -> new String[] {"rewrite.output-forced", "rewrite.committed"};
                    };
            for (String phase : phases) {
                Path run = directory.resolve(action + phase);
                Files.createDirectories(run);
                Process child = new ProcessBuilder(
                                Path.of(
                                                System.getProperty("java.home"),
                                                "bin",
                                                System.getProperty("os.name").startsWith("Windows")
                                                        ? "java.exe"
                                                        : "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("trace.testClasspath"),
                                StorageCrashWorker.class.getName(),
                                run.toString(),
                                action,
                                phase)
                        .redirectErrorStream(true)
                        .redirectOutput(run.resolve("worker.log").toFile())
                        .start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (!Files.exists(run.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline)
                        Thread.sleep(20);
                    assertThat(Files.exists(run.resolve("ready")))
                            .as(Files.readString(run.resolve("worker.log")))
                            .isTrue();
                    assertThat(Files.readString(run.resolve("ready"))).isEqualTo(phase);
                    child.destroyForcibly();
                    assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.exitValue()).isNotZero();
                    if (action.equals("migration")) {
                        try (var c = java.sql.DriverManager.getConnection(
                                        "jdbc:sqlite:" + run.resolve("store/manifest.db"));
                                var s = c.createStatement();
                                var rows = s.executeQuery("SELECT v FROM meta WHERE k='format_version'")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).isEqualTo(phase.endsWith("committed") ? 3 : 2);
                        }
                    }
                    for (int reopening = 0; reopening < 2; reopening++) {
                        try (var store = SqliteEventStore.open(run.resolve("store"))) {
                            if (action.equals("migration")) {
                                assertThat(store.appliedLsn()).isEqualTo(123);
                                assertThat(store.stats().hotRows()).isEqualTo(1);
                                try (var c = java.sql.DriverManager.getConnection(
                                                "jdbc:sqlite:" + run.resolve("store/manifest.db"));
                                        var s = c.createStatement();
                                        var rows = s.executeQuery(
                                                "SELECT w,c,k,p,b,a,actor,cause,kind,lsn FROM hot_event")) {
                                    assertThat(rows.next()).isTrue();
                                    long[] expected = {1, 0, 117964800000000000L, 0, 1, 2, 16, 1, 1, 123};
                                    for (int column = 0; column < expected.length; column++)
                                        assertThat(rows.getLong(column + 1)).isEqualTo(expected[column]);
                                    assertThat(rows.next()).isFalse();
                                }
                                continue;
                            }
                            if (action.equals("exhaustion")) {
                                long lossStart = Long.parseLong(Files.readString(run.resolve("loss-start")));
                                assertThat(CaptureRecovery.recordLoss(run.resolve("rings/capture.loss"), store))
                                        .isTrue();
                                var gaps = store.gapsBetween(lossStart, lossStart + 1);
                                assertThat(gaps).hasSize(reopening + 1);
                                assertThat(gaps).allSatisfy(gap -> {
                                    assertThat(gap.reason()).isEqualTo(GapRecord.Reason.OVERFLOW);
                                    assertThat(gap.droppedCount()).isEqualTo(1);
                                    assertThat(gap.fromMillis()).isLessThanOrEqualTo(lossStart);
                                    assertThat(gap.toMillis()).isGreaterThanOrEqualTo(lossStart);
                                });
                                continue;
                            }
                            if (action.equals("blob")) {
                                String id = Files.readString(run.resolve("blob-id"));
                                assertThat(store.readBlob(id)).containsExactly(0, 1, -1);
                                assertThat(store.blobAt(1, StorageCrashWorker.blobPosition()))
                                        .isEqualTo(phase.equals("blob.reference") ? id : null);
                                assertThat(store.verify().blobsVerified()).isEqualTo(1);
                                continue;
                            }
                            boolean purged = action.equals("purge") && phase.endsWith("committed");
                            assertThat(store.stats().sealedRows()).isEqualTo(purged ? 1 : 2);
                            assertThat(store.stats().shardCount()).isEqualTo(phase.endsWith("committed") ? 1 : 2);
                            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100)
                                            .isEmpty())
                                    .isEqualTo(!purged);
                            assertThat(store.verify().quarantined()).isZero();
                        }
                    }
                } finally {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                        child.waitFor();
                    }
                }
            }
        }
    }
}
