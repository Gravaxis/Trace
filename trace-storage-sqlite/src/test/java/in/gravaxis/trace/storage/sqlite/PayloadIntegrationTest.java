/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.*;

import in.gravaxis.trace.core.capture.BoundedPayloadQueue;
import in.gravaxis.trace.core.journal.JournalReader;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.PayloadHandoff;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.PayloadFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void firstLossWithOnlyOnePersistedBoundRefusesTheWholeWindow() throws Exception {
        Path file = directory.resolve("queue");
        try (var queue = BoundedPayloadQueue.open(file, 1, 8)) {
            assertThat(queue.rejected()).isZero();
            assertThat(queue.lossToMillis()).isEqualTo(Long.MIN_VALUE);
        }
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            var bytes = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .putLong(100);
            bytes.flip();
            channel.write(bytes, 32);
        }
        try (var queue = BoundedPayloadQueue.open(file, 1, 8);
                var store = SqliteEventStore.open(directory.resolve("store"))) {
            new PayloadHandoff(queue).recordLoss(store);
            assertThat(store.gapsBetween(100, 200)).singleElement().satisfies(gap -> {
                assertThat(gap.fromMillis()).isEqualTo(Long.MIN_VALUE);
                assertThat(gap.toMillis()).isEqualTo(Long.MAX_VALUE);
                assertThat(gap.droppedCount()).isEqualTo(-1);
            });
            assertThat(queue.rejected()).isZero();
        }
    }

    @Test
    void transactionAndGapFailuresRetainQueueUntilSuccessfulRetry() throws Exception {
        try (var store = SqliteEventStore.open(directory.resolve("store"));
                var queue = BoundedPayloadQueue.open(directory.resolve("queue"), 1, 8);
                var journal = JournalWriter.open(directory.resolve("journal"), 4096, 1)) {
            assertThat(PayloadFixture.offer(queue)).isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            assertThat(queue.offer(0, 0, 0, 0, 0, new byte[0], 0, 100, 200)).isEqualTo(BoundedPayloadQueue.Offer.FULL);
            var handoff = new PayloadHandoff(queue);
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("store/manifest.db"));
                    var s = c.createStatement()) {
                s.execute(
                        "CREATE TRIGGER fail_payload_gap BEFORE INSERT ON gap BEGIN SELECT RAISE(ABORT,'payload gap fault'); END");
                assertThatThrownBy(() -> handoff.drain(journal, store, Long.MAX_VALUE, 1))
                        .isInstanceOf(StoreException.class)
                        .hasStackTraceContaining("payload gap fault");
                assertThat(queue.pending()).isEqualTo(1);
                assertThat(store.appliedLsn()).isEqualTo(-1);
                assertThat(journal.nextLsn()).isZero();
                s.execute("DROP TRIGGER fail_payload_gap");
                s.execute(
                        "CREATE TRIGGER fail_payload_ref BEFORE INSERT ON blob_ref BEGIN SELECT RAISE(ABORT,'payload ref fault'); END");
                assertThatThrownBy(() -> handoff.drain(journal, store, Long.MAX_VALUE, 1))
                        .isInstanceOf(StoreException.class)
                        .hasStackTraceContaining("payload ref fault");
                assertThat(journal.forcedLsn()).isGreaterThan(0);
                assertThat(queue.pending()).isEqualTo(1);
                assertThat(store.appliedLsn()).isEqualTo(-1);
                assertThat(store.stats().hotRows()).isZero();
                assertThat(store.blobAt(1, PayloadFixture.cursor())).isNull();
                s.execute("DROP TRIGGER fail_payload_ref");
            }
            assertThat(handoff.drain(journal, store, Long.MAX_VALUE, 1)).isEqualTo(1);
            assertThat(queue.pending()).isZero();
            assertThat(handoff.drain(journal, store, Long.MAX_VALUE, 1)).isZero();
            assertThat(store.stats().hotRows()).isEqualTo(1);
            assertThat(store.readBlob(java.util.Objects.requireNonNull(store.blobAt(1, PayloadFixture.cursor()))))
                    .containsExactly(PayloadFixture.event().payload());
            assertThat(store.gapsBetween(100, 200)).singleElement().satisfies(gap -> {
                assertThat(gap.fromMillis()).isEqualTo(100);
                assertThat(gap.toMillis()).isEqualTo(200);
                assertThat(gap.droppedCount()).isEqualTo(1);
            });
        }
    }

    @Test
    void journalFailureDoesNotAcknowledgeOrPublishTheEvent() throws Exception {
        try (var store = SqliteEventStore.open(directory.resolve("store"));
                var queue = BoundedPayloadQueue.open(directory.resolve("queue"), 1, 8)) {
            assertThat(PayloadFixture.offer(queue)).isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            var journal = JournalWriter.open(directory.resolve("journal"), 4096, 1);
            journal.close();
            var handoff = new PayloadHandoff(queue);
            assertThatThrownBy(() -> handoff.drain(journal, store, Long.MAX_VALUE, 1))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(queue.pending()).isEqualTo(1);
            assertThat(store.appliedLsn()).isEqualTo(-1);
            assertThat(store.stats().hotRows()).isZero();
            try (var reopened = JournalWriter.open(directory.resolve("journal"), 4096, 2)) {
                assertThat(handoff.drain(reopened, store, Long.MAX_VALUE, 1)).isEqualTo(1);
                assertThat(queue.pending()).isZero();
            }
        }
    }

    @Test
    void realProcessKillsRecoverExactPayloadAndLossBoundsAtEveryAnnouncedBoundary() throws Exception {
        for (String phase : new String[] {
            "capture.queued", "capture.journal", "capture.row", "capture.committed", "capture.acknowledged"
        }) {
            Path run = directory.resolve(phase);
            Files.createDirectories(run);
            Process child = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            "-cp",
                            System.getProperty("trace.testClasspath"),
                            StorageCrashWorker.class.getName(),
                            run.toString(),
                            "captured",
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
                assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).isNotZero();
                for (int reopen = 0; reopen < 2; reopen++) {
                    try (var store = SqliteEventStore.open(run.resolve("store"));
                            var queue = BoundedPayloadQueue.open(run.resolve("payload.queue"), 1, 8)) {
                        if (reopen == 0) {
                            assertThat(queue.pending()).isEqualTo(phase.equals("capture.acknowledged") ? 0 : 1);
                            assertThat(store.stats().hotRows())
                                    .isEqualTo(
                                            phase.equals("capture.committed") || phase.equals("capture.acknowledged")
                                                    ? 1
                                                    : 0);
                        }
                        assertThat(queue.rejected(BoundedPayloadQueue.Offer.FULL))
                                .isEqualTo(1);
                        assertThat(queue.rejected(BoundedPayloadQueue.Offer.OVERSIZED))
                                .isEqualTo(1);
                        JournalReader.replay(
                                run.resolve("journal"), store.appliedLsn() + 1, PayloadHandoff.replayInto(store));
                        try (var journal = JournalWriter.open(run.resolve("journal"), 4096, 2, store.appliedLsn())) {
                            int drained = new PayloadHandoff(queue).drain(journal, store, Long.MAX_VALUE, 1);
                            assertThat(drained).isEqualTo(reopen == 0 && !phase.equals("capture.acknowledged") ? 1 : 0);
                        }
                        assertThat(queue.pending()).isZero();
                        assertThat(store.stats().hotRows()).isEqualTo(1);
                        assertThat(store.readBlob(
                                        java.util.Objects.requireNonNull(store.blobAt(1, PayloadFixture.cursor()))))
                                .containsExactly(PayloadFixture.event().payload());
                        var batch = new MutationBatch(2);
                        try (var cursor = store.scan(PayloadFixture.plan())) {
                            assertThat(cursor.next(batch)).isTrue();
                            assertThat(batch.size()).isEqualTo(1);
                            assertThat(batch.x(0)).isEqualTo(2);
                            assertThat(batch.y(0)).isEqualTo(64);
                            assertThat(batch.z(0)).isEqualTo(3);
                            assertThat(batch.beforeState(0)).isEqualTo(10);
                            assertThat(batch.afterState(0)).isEqualTo(11);
                            assertThat(batch.actorId(0)).isEqualTo(16);
                            assertThat(batch.timestamp(0)).isEqualTo(PayloadFixture.TIME);
                            assertThat(batch.sequence(0)).isEqualTo(1);
                            assertThat(batch.cause(0)).isEqualTo(2);
                            assertThat(batch.kind(0)).isEqualTo(1);
                            assertThat(cursor.next(batch)).isFalse();
                        }
                        assertThat(store.gapsBetween(90, 210)).isNotEmpty().allSatisfy(gap -> {
                            assertThat(gap.fromMillis()).isLessThanOrEqualTo(90);
                            assertThat(gap.toMillis()).isGreaterThanOrEqualTo(210);
                        });
                    }
                }
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(20, TimeUnit.SECONDS);
                }
            }
        }
    }
}
