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

import in.gravaxis.trace.storage.StoreException;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageMigrationTest {
    @TempDir
    Path directory;

    @Test
    void copiesLegacyHotRowsOnceWithTheirWatermark() throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"))) {
            SqliteSchema.createManifest(c);
            try (var s = c.createStatement()) {
                s.execute("INSERT INTO meta VALUES ('format_version','2'),('applied_lsn','123')");
            }
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("hot.db"))) {
            SqliteSchema.createHot(c, "main");
            try (var s = c.createStatement()) {
                s.execute("INSERT INTO hot_event VALUES(1,0,117964800000000000,0,1,2,16,1,1,123)");
            }
        }
        try (var store = SqliteEventStore.open(directory)) {
            assertThat(store.appliedLsn()).isEqualTo(123);
            assertThat(store.stats().hotRows()).isEqualTo(1);
        }
        try (var store = SqliteEventStore.open(directory)) {
            assertThat(store.stats().hotRows()).isEqualTo(1);
            assertThat(store.seal()).isEqualTo(1);
            assertThat(store.stats().sealedRows()).isEqualTo(1);
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                var s = c.createStatement();
                var r = s.executeQuery("SELECT v FROM meta WHERE k='format_version'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getInt(1)).isGreaterThan(2);
        }
    }

    @Test
    void refusesNewerFormatBeforeCreatingTablesAndReleasesLock() throws Exception {
        Path manifest = directory.resolve("manifest.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + manifest);
                var s = c.createStatement()) {
            s.execute("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");
            s.execute("INSERT INTO meta VALUES('format_version','999')");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> SqliteEventStore.open(directory))
                    .isInstanceOfSatisfying(
                            StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CORRUPT));
        }
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + manifest);
                var s = c.createStatement();
                var r = s.executeQuery("SELECT count(*) FROM sqlite_schema WHERE type='table'")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getInt(1)).isEqualTo(1);
        }
    }
}
