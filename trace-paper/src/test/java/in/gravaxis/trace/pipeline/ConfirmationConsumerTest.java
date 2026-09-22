/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.pipeline;

import static org.assertj.core.api.Assertions.*;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfirmationConsumerTest {
    @TempDir
    Path directory;

    @Test
    void flushRefusesFailedConfirmationGapAndRetryPersistsItWithoutInventedRows() throws Exception {
        long before;
        try (var capture = new CaptureService(directory.resolve("rings"), 16);
                var journal = JournalWriter.open(directory.resolve("journal"), 4096, 1);
                var store = SqliteEventStore.open(directory.resolve("store"));
                var connection =
                        java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("store/manifest.db"));
                var statement = connection.createStatement()) {
            before = System.currentTimeMillis();
            statement.execute(
                    "CREATE TRIGGER fail_confirmation_gap BEFORE INSERT ON gap BEGIN SELECT RAISE(ABORT,'confirmation gap fault'); END");
            capture.captureBlockChange(1, 1, 64, 1, 1, 2, 1, 1);
            capture.confirmStaged((w, x, y, z) -> CaptureService.UNKNOWN_STATE);
            assertThat(capture.droppedUnreadable()).isEqualTo(1);
            assertThat(capture.published()).isZero();
            var consumer = new StoreConsumer(
                    capture,
                    journal,
                    store,
                    org.slf4j.LoggerFactory.getLogger(ConfirmationConsumerTest.class),
                    60000,
                    60000);
            Thread worker = new Thread(consumer, "confirmation-consumer-test");
            worker.start();
            try {
                assertThatThrownBy(() -> consumer.flushAndSeal(10000)).isInstanceOf(StoreException.class);
                assertThat(store.gapsBetween(before, Long.MAX_VALUE)).isEmpty();
                statement.execute("DROP TRIGGER fail_confirmation_gap");
                consumer.flushAndSeal(10000);
                var gaps = store.gapsBetween(before, Long.MAX_VALUE);
                assertThat(gaps).hasSize(1);
                assertThat(gaps.getFirst().fromMillis()).isLessThanOrEqualTo(before);
                assertThat(gaps.getFirst().toMillis()).isGreaterThanOrEqualTo(before);
                assertThat(gaps.getFirst().droppedCount()).isEqualTo(1);
                assertThat(store.stats().hotRows()).isZero();
                assertThat(store.stats().sealedRows()).isZero();
                consumer.flushAndSeal(10000);
                assertThat(store.gapsBetween(before, Long.MAX_VALUE)).hasSize(1);
            } finally {
                consumer.stop();
                worker.join(10000);
                assertThat(worker.isAlive()).isFalse();
            }
        }
        try (var reopened = SqliteEventStore.open(directory.resolve("store"))) {
            assertThat(reopened.gapsBetween(before, Long.MAX_VALUE)).hasSize(1);
            assertThat(reopened.stats().sealedRows()).isZero();
        }
    }
}
