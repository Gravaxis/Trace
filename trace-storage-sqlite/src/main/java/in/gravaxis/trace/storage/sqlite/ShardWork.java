/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.storage.MaintenanceBudget;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Unpublished work survives yields, not process death; live inputs remain authoritative (ADR-0022). */
final class ShardWork implements AutoCloseable {
    record Source(
            long id,
            Path file,
            long base,
            long count,
            long min,
            long max,
            @Nullable String digest) {}

    final List<Source> sources;
    final Path output;
    final boolean verifyOnly;
    final long hotEnd;
    final long cutoff;
    long copied;
    long removed;
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    long failingShard;
    String content = "";
    boolean done;
    private @Nullable Connection target;
    private int sourceIndex;
    private long world = -1, chunk, key, hotRow;
    private long seen;
    private long sourceMin = Long.MAX_VALUE, sourceMax = Long.MIN_VALUE;
    private RowDigest input = new RowDigest();
    private final RowDigest preserved = new RowDigest();
    private final RowDigest reread = new RowDigest();
    private boolean checking;
    private boolean published;

    ShardWork(List<Source> sources, Path output, boolean verifyOnly, long hotEnd, long cutoff) throws SQLException {
        this.sources = sources;
        this.output = output;
        this.verifyOnly = verifyOnly;
        this.hotEnd = hotEnd;
        this.cutoff = cutoff;
        if (!verifyOnly) {
            Connection opened = DriverManager.getConnection("jdbc:sqlite:" + output);
            try {
                SqliteSchema.applySealPragmas(opened);
                SqliteSchema.createShard(opened);
                opened.setAutoCommit(false);
                target = opened;
            } catch (SQLException e) {
                opened.close();
                try {
                    Files.deleteIfExists(output);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
    }

    long step(Connection manifest, MaintenanceBudget budget) throws SQLException, IOException {
        long start = System.nanoTime();
        long processed = 0;
        // A row boundary preserves progress; no deadline callback can invalidate half of a digest.
        while (!done
                && processed < budget.maxInputRows()
                && !budget.cancelled().getAsBoolean()
                && !Thread.currentThread().isInterrupted()
                && System.nanoTime() - start < budget.maxMillis() * 1_000_000L) {
            int limit = (int) Math.min(256, budget.maxInputRows() - processed);
            if (checking) {
                try (Connection reader = read(output)) {
                    long count = page(reader, false, 0, limit, true);
                    processed += count;
                    if (count < limit) {
                        if (!preserved.multiset().equals(reread.multiset()))
                            throw new SQLException("Incremental output multiset mismatch");
                        content = reread.finish();
                        done = true;
                    }
                }
            } else if (hotEnd > 0) {
                long count = page(manifest, true, 0, limit, false);
                processed += count;
                if (count < limit) finishCopy();
            } else if (sourceIndex < sources.size()) {
                Source source = sources.get(sourceIndex);
                failingShard = source.id();
                try (Connection reader = read(source.file())) {
                    long count = page(reader, false, source.base(), limit, false);
                    processed += count;
                    if (count < limit) {
                        if (seen != source.count()
                                || (seen > 0 && (sourceMin != source.min() || sourceMax != source.max()))
                                || (source.digest() != null && !source.digest().equals(input.finish())))
                            throw new SQLException("Incremental input verification failed");
                        sourceIndex++;
                        resetCursor();
                        input = new RowDigest();
                        seen = 0;
                        sourceMin = Long.MAX_VALUE;
                        sourceMax = Long.MIN_VALUE;
                    }
                }
                failingShard = 0;
            } else finishCopy();
        }
        return processed;
    }

    private long page(Connection reader, boolean hot, long base, int limit, boolean check) throws SQLException {
        String sql = "SELECT w,c,k,p,b,a,actor,cause,kind" + (hot ? ",rowid" : "")
                + " FROM "
                + (hot
                        ? "hot_event WHERE rowid>? AND rowid<=? ORDER BY rowid"
                        : "ev WHERE (w,c,k)>(?,?,?) ORDER BY w,c,k")
                + " LIMIT ?";
        Connection destination = target;
        try (var select = reader.prepareStatement(sql);
                var insert = destination == null
                        ? null
                        : destination.prepareStatement("INSERT INTO ev VALUES(?,?,?,?,?,?,?,?,?)")) {
            if (hot) {
                select.setLong(1, hotRow);
                select.setLong(2, hotEnd);
                select.setInt(3, limit);
            } else {
                select.setLong(1, world);
                select.setLong(2, chunk);
                select.setLong(3, key);
                select.setInt(4, limit);
            }
            long count = 0;
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    long timestamp = ShardKeys.timestampOf(base, rows.getLong(3));
                    if (check) reread.add(rows, base);
                    else {
                        input.add(rows, base);
                        seen++;
                        sourceMin = Math.min(sourceMin, timestamp);
                        sourceMax = Math.max(sourceMax, timestamp);
                        if (timestamp < cutoff) removed++;
                        else if (!verifyOnly) {
                            if (insert == null) throw new IllegalStateException("Output already closed");
                            for (int i = 1; i <= 9; i++) insert.setLong(i, rows.getLong(i));
                            insert.setLong(3, ShardKeys.key(0, timestamp, ShardKeys.sequenceOf(rows.getLong(3))));
                            insert.executeUpdate();
                            preserved.add(rows, base);
                            copied++;
                            min = Math.min(min, timestamp);
                            max = Math.max(max, timestamp);
                        }
                    }
                    world = rows.getLong(1);
                    chunk = rows.getLong(2);
                    key = rows.getLong(3);
                    if (hot) hotRow = rows.getLong(10);
                    count++;
                }
            }
            return count;
        }
    }

    private void finishCopy() throws SQLException, IOException {
        if (verifyOnly) {
            done = true;
            return;
        }
        Connection destination = target;
        if (destination == null) throw new IllegalStateException("Output already closed");
        try (var s = destination.prepareStatement("INSERT INTO meta VALUES(?,?)")) {
            for (String[] entry :
                    new String[][] {{"base_ts", "0"}, {"format_version", "3"}, {"row_count", Long.toString(copied)}}) {
                s.setString(1, entry[0]);
                s.setString(2, entry[1]);
                s.executeUpdate();
            }
        }
        destination.commit();
        destination.close();
        target = null;
        try (var channel = FileChannel.open(output, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        checking = true;
        resetCursor();
    }

    private void resetCursor() {
        world = -1;
        chunk = 0;
        key = 0;
    }

    private static Connection read(Path path) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro");
        try {
            SqliteSchema.applyReadPragmas(connection);
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    void published() {
        published = true;
    }

    @Override
    public void close() throws SQLException, IOException {
        Connection destination = target;
        target = null;
        try {
            if (destination != null) destination.close();
        } finally {
            if (!published && !verifyOnly) Files.deleteIfExists(output);
        }
    }
}
