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

import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStorageTest {
    @TempDir
    Path directory;

    @Test
    void opaquePayloadAndReferenceSurviveReopenMergeAndPartialPurge() throws Exception {
        byte[] payload = {0, -1, 2, 0, 3};
        String id;
        CursorPosition first =
                new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T, 1);
        CursorPosition second =
                new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T + 1, 2);
        try (var store = SqliteEventStore.open(directory)) {
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            store.seal();
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 1, 17))), 1);
            store.seal();
            id = store.putBlob(7, payload);
            assertThat(store.putBlob(7, payload)).isEqualTo(id);
            assertThat(store.putBlob(8, payload)).isNotEqualTo(id);
            store.attachBlob(1, first, id);
            store.attachBlob(1, second, id);
            assertThat(store.compact()).isEqualTo(2);
            assertThat(store.blobAt(1, first)).isEqualTo(id);
            assertThat(store.purgeActor(16, ShardMaintenanceTest.T, ShardMaintenanceTest.T + 2))
                    .isEqualTo(1);
            assertThat(store.blobAt(1, first)).isNull();
            assertThat(store.blobAt(1, second)).isEqualTo(id);
            store.collectBlobs();
            assertThat(store.readBlob(id)).containsExactly(payload);
        }
        try (var store = SqliteEventStore.open(directory)) {
            assertThat(store.blobAt(1, second)).isEqualTo(id);
            assertThat(store.readBlob(id)).containsExactly(payload);
            assertThat(store.purgeActor(17, ShardMaintenanceTest.T, ShardMaintenanceTest.T + 2))
                    .isEqualTo(1);
            assertThat(store.collectBlobs()).isEqualTo(1);
            assertThatThrownBy(() -> store.readBlob(id)).isInstanceOf(StoreException.class);
            String empty = store.putBlob(1, new byte[0]);
            assertThat(store.readBlob(empty)).isEmpty();
        }
    }

    @Test
    void corruptedPayloadCannotBeReadOrAttachedAndMissingEventIsRejected() throws Exception {
        String id;
        try (var store = SqliteEventStore.open(directory)) {
            id = store.putBlob(1, new byte[] {1, 2, 3});
            assertThatThrownBy(() -> store.attachBlob(
                            1,
                            new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T, 1),
                            id))
                    .isInstanceOf(StoreException.class);
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            store.attachBlob(
                    1, new CursorPosition(in.gravaxis.trace.core.geom.Morton.key(0, 0), ShardMaintenanceTest.T, 1), id);
            assertThat(store.verify().blobsVerified()).isEqualTo(1);
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                var s = c.createStatement()) {
            s.execute("UPDATE blob SET payload=x'000000'");
        }
        try (var store = SqliteEventStore.open(directory)) {
            assertThatThrownBy(() -> store.readBlob(id))
                    .isInstanceOfSatisfying(
                            StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CORRUPT));
            assertThat(store.verify().blobsCorrupt()).isEqualTo(1);
            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 1))
                    .hasSize(1);
            assertThat(store.verify().blobsCorrupt()).isEqualTo(1);
            assertThat(store.gapsBetween(ShardMaintenanceTest.T, ShardMaintenanceTest.T + 1))
                    .hasSize(1);
        }
    }
}
