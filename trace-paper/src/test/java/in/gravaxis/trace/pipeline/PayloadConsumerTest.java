/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.pipeline;

import static org.assertj.core.api.Assertions.*;

import in.gravaxis.trace.core.capture.BoundedPayloadQueue;
import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.storage.MaintenancePolicy;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import in.gravaxis.trace.storage.testing.PayloadFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadConsumerTest {
    @TempDir
    Path directory;

    @Test
    void failedPayloadBlocksLaterRingWatermarkAndFlushUntilRetrySucceeds() throws Exception {
        try (var capture = new CaptureService(directory.resolve("rings"), 16);
                var queue = BoundedPayloadQueue.open(directory.resolve("queue"), 1, 8);
                var journal = JournalWriter.open(directory.resolve("journal"), 4096, 1);
                var store = SqliteEventStore.open(directory.resolve("store"));
                var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("store/manifest.db"));
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TRIGGER failed_handoff BEFORE INSERT ON blob_ref BEGIN SELECT RAISE(ABORT,'consumer payload fault'); END");
            assertThat(PayloadFixture.offer(queue)).isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            capture.captureBlockChange(1, 5, 64, 5, 1, 2, 1, 1);
            capture.confirmStaged((w, x, y, z) -> 2);
            assertThat(capture.published()).isEqualTo(1);
            var consumer = new StoreConsumer(
                    capture,
                    journal,
                    store,
                    org.slf4j.LoggerFactory.getLogger(PayloadConsumerTest.class),
                    200,
                    60000,
                    MaintenancePolicy.defaults(),
                    List.of(queue));
            Thread worker = new Thread(consumer, "payload-consumer-test");
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (consumer.storeFailures() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(consumer.storeFailures()).isGreaterThan(0);
                assertThat(store.appliedLsn()).isEqualTo(-1);
                assertThat(store.stats().hotRows()).isZero();
                assertThat(queue.pending()).isEqualTo(1);
                assertThat(capture.rings().getFirst().pending()).isEqualTo(1);
                assertThatThrownBy(() -> consumer.flushAndSeal(100))
                        .isInstanceOf(in.gravaxis.trace.storage.StoreException.class);
                s.execute("DROP TRIGGER failed_handoff");
                consumer.flushAndSeal(10000);
                assertThat(queue.pending()).isZero();
                assertThat(capture.rings().getFirst().pending()).isZero();
                assertThat(store.stats().sealedRows()).isEqualTo(2);
                assertThat(store.readBlob(java.util.Objects.requireNonNull(store.blobAt(1, PayloadFixture.cursor()))))
                        .containsExactly(PayloadFixture.event().payload());
            } finally {
                consumer.stop();
                worker.join(10000);
                assertThat(worker.isAlive()).isFalse();
            }
        }
    }
}
