/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The on-disk shape of the embedded store, and the pragmas that make it behave.
 *
 * <p>Two things here are load-bearing.
 *
 * <p>Sealed shards are {@code WITHOUT ROWID} with the primary key {@code (world, chunk, key)}, so
 * the table <em>is</em> the index and a cuboid rollback is a handful of contiguous range scans
 * rather than an index lookup per position. The hot window is the opposite: a plain heap table with
 * no index at all, because random-key insertion into a clustered table is the one thing SQLite is
 * genuinely bad at. Rows are sorted once, at seal time, and bulk-loaded in order.
 *
 * <p>Pragmas are set with SQL rather than driver setters. Paper bundles its own sqlite-jdbc and
 * plugin classloaders are parent-first, so the driver version is not ours to choose; a setter added
 * in a later release would fail at runtime on the version the server actually has. See ADR-0003.
 */
final class SqliteSchema {

    /** Bumped when the on-disk format changes in a way a previous build cannot read. */
    static final int FORMAT_VERSION = 1;

    private SqliteSchema() {}

    /** Pragmas for the writable databases: the manifest and the hot window. */
    static void applyWritePragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            // Durable against a crashing server; a power cut can still cost the last transaction,
            // which is why the journal in front of this store exists. Documented, not hidden.
            statement.execute("PRAGMA synchronous=NORMAL");
            // Checkpointing is a maintenance decision, not something to do inside a batch commit.
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    /** Pragmas for building a sealed shard: nothing to recover, because it is not published yet. */
    static void applySealPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=OFF");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA locking_mode=EXCLUSIVE");
            statement.execute("PRAGMA temp_store=MEMORY");
            statement.execute("PRAGMA page_size=16384");
        }
    }

    /** Pragmas for reading a sealed shard, which is immutable by construction. */
    static void applyReadPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=1");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    static void createManifest(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS meta(
                      k TEXT PRIMARY KEY,
                      v TEXT NOT NULL
                    ) WITHOUT ROWID""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS shard(
                      id INTEGER PRIMARY KEY,
                      file TEXT NOT NULL UNIQUE,
                      base_ts INTEGER NOT NULL,
                      min_ts INTEGER NOT NULL,
                      max_ts INTEGER NOT NULL,
                      row_count INTEGER NOT NULL,
                      bytes INTEGER NOT NULL,
                      state INTEGER NOT NULL,
                      created_at INTEGER NOT NULL)""");
            statement.execute("CREATE INDEX IF NOT EXISTS shard_window ON shard(state, max_ts, min_ts)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gap(
                      id INTEGER PRIMARY KEY,
                      from_ts INTEGER NOT NULL,
                      to_ts INTEGER NOT NULL,
                      reason INTEGER NOT NULL,
                      dropped INTEGER NOT NULL,
                      detail TEXT NOT NULL)""");
            statement.execute("CREATE INDEX IF NOT EXISTS gap_window ON gap(to_ts, from_ts)");
        }
    }

    /**
     * The hot window: a heap table, deliberately without an index.
     *
     * <p>Attached to the manifest connection so that publishing a shard and forgetting the rows it
     * came from happen in one transaction. SQLite commits across attached databases atomically,
     * which is the only reason the seal has no window where a row exists in both places.
     */
    static void createHot(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s.hot_event(
                      w INTEGER NOT NULL,
                      c INTEGER NOT NULL,
                      k INTEGER NOT NULL,
                      p INTEGER NOT NULL,
                      b INTEGER NOT NULL,
                      a INTEGER NOT NULL,
                      actor INTEGER NOT NULL,
                      cause INTEGER NOT NULL,
                      kind INTEGER NOT NULL,
                      lsn INTEGER NOT NULL)""".formatted(schema));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s.meta(
                      k TEXT PRIMARY KEY,
                      v TEXT NOT NULL
                    ) WITHOUT ROWID""".formatted(schema));
        }
    }

    static void createShard(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE ev(
                      w INTEGER NOT NULL,
                      c INTEGER NOT NULL,
                      k INTEGER NOT NULL,
                      p INTEGER NOT NULL,
                      b INTEGER NOT NULL,
                      a INTEGER NOT NULL,
                      actor INTEGER NOT NULL,
                      cause INTEGER NOT NULL,
                      kind INTEGER NOT NULL,
                      PRIMARY KEY (w, c, k)
                    ) WITHOUT ROWID""");
            statement.execute("""
                    CREATE TABLE meta(
                      k TEXT PRIMARY KEY,
                      v TEXT NOT NULL
                    ) WITHOUT ROWID""");
        }
    }
}
