/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

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
        for (String action : new String[] {"compact", "purge", "blob"}) {
            String[] phases = action.equals("blob")
                    ? new String[] {"blob.payload", "blob.reference"}
                    : new String[] {"rewrite.output-forced", "rewrite.committed"};
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
                    for (int reopening = 0; reopening < 2; reopening++) {
                        try (var store = SqliteEventStore.open(run.resolve("store"))) {
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
