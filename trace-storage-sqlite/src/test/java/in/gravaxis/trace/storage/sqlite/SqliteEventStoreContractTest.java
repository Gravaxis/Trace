/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.testing.EventStoreContract;
import java.nio.file.Path;

/** The embedded default, held to the shared storage contract. */
class SqliteEventStoreContractTest extends EventStoreContract {

    @Override
    protected AutoCloseable failCaptureReference() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TRIGGER contract_capture BEFORE INSERT ON blob_ref BEGIN SELECT RAISE(ABORT,'contract capture fault'); END");
        }
        return () -> {
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER contract_capture");
            }
        };
    }

    @Override
    protected long firstLiveShard() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT id FROM shard WHERE state=0 ORDER BY id LIMIT 1")) {
            if (!rows.next()) throw new AssertionError("Fixture did not seal a shard");
            return rows.getLong(1);
        }
    }

    @Override
    protected AutoCloseable failQuarantineGap() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TRIGGER contract_fail_gap BEFORE INSERT ON gap BEGIN SELECT RAISE(ABORT,'contract fault'); END");
        }
        return () -> {
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("manifest.db"));
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER contract_fail_gap");
            }
        };
    }

    @Override
    protected EventStore open(Path directory) throws StoreException {
        return SqliteEventStore.open(directory);
    }
}
