/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.StoreStats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Tier S: the default store. Time-sharded SQLite, clustered by chunk.
 *
 * <p>Writes land in an unindexed hot table and are moved into sealed, immutable shard files in
 * sorted order. Random-key insertion into a clustered table is SQLite's worst case and a pre-sorted
 * bulk load is its best, so the store never does the former: it pays the sort once, at seal time.
 *
 * <p>Publishing a shard and forgetting the hot rows it was built from happen in a single
 * transaction across the manifest and the attached hot database, so there is no window in which a
 * row is in both places or in neither. The shard file itself is written and flushed before that
 * transaction begins; if the server dies in between, the file is an orphan with no manifest row and
 * is deleted on the next start.
 *
 * <p>One writer per data directory, enforced with a lock file. A network wanting shared history
 * runs an external tier — the build spec is explicit that this belongs on page one of the
 * documentation, not in a footnote.
 */
public final class SqliteEventStore implements EventStore {

    private static final String HOT_SCHEMA = "hot";
    private static final String META_APPLIED_LSN = "applied_lsn";
    private static final String META_FORMAT = "format_version";
    private static final int STATE_LIVE = 0;

    /** No frame has been applied yet. Journal positions start at zero, so this cannot be zero. */
    private static final long NOTHING_APPLIED = -1L;

    private final Path directory;
    private final Path shardDirectory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection writer;

    private long appliedLsn;

    private SqliteEventStore(Path directory, FileChannel lockChannel, FileLock lock, Connection writer) {
        this.directory = directory;
        this.shardDirectory = directory.resolve("shards");
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.writer = writer;
    }

    /**
     * Opens (and creates, if needed) the store in {@code directory}.
     *
     * @throws StoreException with {@link StoreException.Reason#LOCKED} if another server already
     *     has it open — which is the failure a network operator needs named clearly, not a stack
     *     trace about a busy database
     */
    public static SqliteEventStore open(Path directory) throws StoreException {
        FileChannel lockChannel = null;
        FileLock lock = null;
        try {
            Files.createDirectories(directory.resolve("shards"));
            lockChannel =
                    FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw alreadyOpen(directory, null);
            }

            Connection connection = DriverManager.getConnection(jdbcUrl(directory.resolve("manifest.db")));
            SqliteSchema.applyWritePragmas(connection);
            SqliteSchema.createManifest(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '%s' AS %s"
                        .formatted(
                                directory
                                        .resolve("hot.db")
                                        .toAbsolutePath()
                                        .toString()
                                        .replace("'", "''"),
                                HOT_SCHEMA));
            }
            SqliteSchema.applyWritePragmas(connection);
            SqliteSchema.createHot(connection, HOT_SCHEMA);
            connection.setAutoCommit(true);

            SqliteEventStore store = new SqliteEventStore(directory, lockChannel, lock, connection);
            store.initialiseMeta();
            store.deleteOrphanShards();
            return store;
        } catch (StoreException e) {
            closeQuietly(lock, lockChannel);
            throw e;
        } catch (SQLException e) {
            closeQuietly(lock, lockChannel);
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not open the store: " + e.getMessage(), e);
        } catch (OverlappingFileLockException e) {
            closeQuietly(lock, lockChannel);
            throw alreadyOpen(directory, e);
        } catch (IOException e) {
            closeQuietly(lock, lockChannel);
            throw new StoreException(
                    StoreException.Reason.DISK, "Could not open " + directory + ": " + e.getMessage(), e);
        }
    }

    /** The same message whether the other holder is another process or this one. */
    private static StoreException alreadyOpen(Path directory, @Nullable Throwable cause) {
        String message = "Another server already has this Trace data directory open: " + directory
                + ". One server per data directory; a network that wants shared history needs an external storage"
                + " tier.";
        return cause == null
                ? new StoreException(StoreException.Reason.LOCKED, message)
                : new StoreException(StoreException.Reason.LOCKED, message, cause);
    }

    private void initialiseMeta() throws SQLException, StoreException {
        String format = readMeta(META_FORMAT);
        if (format == null) {
            writeMeta(META_FORMAT, Integer.toString(SqliteSchema.FORMAT_VERSION));
        } else if (Integer.parseInt(format) > SqliteSchema.FORMAT_VERSION) {
            throw new StoreException(
                    StoreException.Reason.CORRUPT,
                    "This data directory was written by a newer Trace (format " + format + ", this build reads "
                            + SqliteSchema.FORMAT_VERSION + "). Downgrading is not supported.");
        }
        String applied = readMeta(META_APPLIED_LSN);
        // Minus one, not zero: the journal's first frame lives at position zero, and a watermark of
        // zero would silently discard it. That cost the first frame of every run until an
        // integration test noticed five missing blocks.
        appliedLsn = applied == null ? NOTHING_APPLIED : Long.parseLong(applied);
    }

    /** A shard file with no manifest row was never published; a crash mid-seal leaves one behind. */
    private void deleteOrphanShards() throws StoreException {
        try {
            List<String> published = new ArrayList<>();
            try (Statement statement = writer.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT file FROM shard")) {
                while (rows.next()) {
                    published.add(rows.getString(1));
                }
            }
            try (var files = Files.list(shardDirectory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".db") && !published.contains(name)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new StoreException(
                    StoreException.Reason.DISK, "Could not clean up unpublished shards: " + e.getMessage(), e);
        }
    }

    @Override
    public void append(RecordBatch batch, long journalPosition) throws StoreException {
        if (batch.count() == 0) {
            return;
        }
        if (journalPosition <= appliedLsn) {
            // Already applied. Replay after a crash re-sends whatever it cannot prove landed, so
            // this is the normal path during recovery, not an error.
            return;
        }
        try {
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement("INSERT INTO " + HOT_SCHEMA
                    + ".hot_event(w, c, k, p, b, a, actor, cause, kind, lsn) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < batch.count(); i++) {
                    long position = batch.position(i);
                    long states = batch.states(i);
                    long actorTime = batch.actorTime(i);
                    long metadata = batch.metadata(i);

                    int x = EventRecords.x(position);
                    int y = EventRecords.y(position);
                    int z = EventRecords.z(position);
                    long timestamp = TraceEpoch.toAbsolute(EventRecords.relativeMillis(actorTime));

                    insert.setInt(1, EventRecords.worldId(metadata));
                    insert.setLong(2, Morton.key(x >> 4, z >> 4));
                    insert.setLong(3, ShardKeys.key(0L, timestamp, EventRecords.sequence(metadata)));
                    insert.setInt(4, ShardKeys.localPosition(x, y, z));
                    insert.setInt(5, EventRecords.beforeState(states));
                    insert.setInt(6, EventRecords.afterState(states));
                    insert.setInt(7, EventRecords.actorId(actorTime, metadata));
                    insert.setInt(8, EventRecords.cause(metadata));
                    insert.setInt(9, EventRecords.kind(metadata));
                    insert.setLong(10, journalPosition);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            writeMeta(META_APPLIED_LSN, Long.toString(journalPosition));
            writer.commit();
            appliedLsn = journalPosition;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException(StoreException.Reason.INTERNAL, "Append failed: " + e.getMessage(), e);
        } finally {
            setAutoCommitQuietly();
        }
    }

    @Override
    public long appliedLsn() {
        return appliedLsn;
    }

    @Override
    public long seal() throws StoreException {
        try {
            long maxRowId;
            long minTs;
            long maxTs;
            long rowCount;
            try (Statement statement = writer.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT COUNT(*), MIN(k), MAX(k), MAX(rowid) FROM " + HOT_SCHEMA + ".hot_event")) {
                // A count over the hot window only: never over the event tables a query reads, which
                // is the anti-pattern this project is measured against.
                rows.next();
                rowCount = rows.getLong(1);
                if (rowCount == 0) {
                    return 0;
                }
                minTs = ShardKeys.timestampOf(0L, rows.getLong(2));
                maxTs = ShardKeys.timestampOf(0L, rows.getLong(3));
                maxRowId = rows.getLong(4);
            }

            long shardId = nextShardId();
            String fileName = "shard-%012d.db".formatted(shardId);
            Path shardFile = shardDirectory.resolve(fileName);
            Files.deleteIfExists(shardFile);
            long written = writeShard(shardFile, minTs, maxRowId);

            publishShard(shardId, fileName, minTs, maxTs, written, Files.size(shardFile), maxRowId);
            return written;
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Seal failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new StoreException(StoreException.Reason.DISK, "Seal failed: " + e.getMessage(), e);
        }
    }

    private long writeShard(Path shardFile, long baseMillis, long maxRowId) throws SQLException, IOException {
        long written = 0;
        try (Connection shard = DriverManager.getConnection(jdbcUrl(shardFile))) {
            SqliteSchema.applySealPragmas(shard);
            SqliteSchema.createShard(shard);
            shard.setAutoCommit(false);
            try (PreparedStatement insert = shard.prepareStatement(
                            "INSERT INTO ev(w,c,k,p,b,a,actor,cause,kind) VALUES (?,?,?,?,?,?,?,?,?)");
                    PreparedStatement select =
                            writer.prepareStatement("SELECT w, c, k, p, b, a, actor, cause, kind FROM " + HOT_SCHEMA
                                    + ".hot_event WHERE rowid <= ? ORDER BY w, c, k")) {
                // Sorted once, here, and bulk-loaded in primary-key order: appends land in the
                // rightmost leaf, so pages end up nearly full instead of half empty.
                select.setLong(1, maxRowId);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        insert.setInt(1, rows.getInt(1));
                        insert.setLong(2, rows.getLong(2));
                        insert.setLong(
                                3,
                                ShardKeys.key(
                                        baseMillis,
                                        ShardKeys.timestampOf(0L, rows.getLong(3)),
                                        ShardKeys.sequenceOf(rows.getLong(3))));
                        insert.setInt(4, rows.getInt(4));
                        insert.setInt(5, rows.getInt(5));
                        insert.setInt(6, rows.getInt(6));
                        insert.setInt(7, rows.getInt(7));
                        insert.setInt(8, rows.getInt(8));
                        insert.setInt(9, rows.getInt(9));
                        insert.addBatch();
                        written++;
                        if (written % 10_000 == 0) {
                            insert.executeBatch();
                        }
                    }
                }
                insert.executeBatch();
            }
            try (PreparedStatement meta = shard.prepareStatement("INSERT INTO meta(k, v) VALUES (?, ?)")) {
                meta.setString(1, "base_ts");
                meta.setString(2, Long.toString(baseMillis));
                meta.addBatch();
                meta.setString(1, META_FORMAT);
                meta.setString(2, Integer.toString(SqliteSchema.FORMAT_VERSION));
                meta.addBatch();
                meta.setString(1, "row_count");
                meta.setString(2, Long.toString(written));
                meta.addBatch();
                meta.executeBatch();
            }
            shard.commit();
        }
        // The file must be on disk before the manifest points at it: a manifest row naming a file
        // that a crash left half-written is the one state this design must never reach.
        try (FileChannel channel = FileChannel.open(shardFile, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        return written;
    }

    private void publishShard(
            long shardId, String fileName, long minTs, long maxTs, long rowCount, long bytes, long maxRowId)
            throws SQLException, StoreException {
        try {
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement(
                    "INSERT INTO shard(id, file, base_ts, min_ts, max_ts, row_count, bytes, state, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                insert.setLong(1, shardId);
                insert.setString(2, fileName);
                insert.setLong(3, minTs);
                insert.setLong(4, minTs);
                insert.setLong(5, maxTs);
                insert.setLong(6, rowCount);
                insert.setLong(7, bytes);
                insert.setInt(8, STATE_LIVE);
                insert.setLong(9, System.currentTimeMillis());
                insert.executeUpdate();
            }
            try (PreparedStatement delete =
                    writer.prepareStatement("DELETE FROM " + HOT_SCHEMA + ".hot_event WHERE rowid <= ?")) {
                delete.setLong(1, maxRowId);
                delete.executeUpdate();
            }
            // One transaction across two attached databases: SQLite commits them atomically, so the
            // rows are never in both places and never in neither.
            writer.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Publishing the shard failed: " + e.getMessage(), e);
        } finally {
            setAutoCommitQuietly();
        }
    }

    private long nextShardId() throws SQLException {
        try (Statement statement = writer.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM shard")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Override
    public MutationCursor scan(ScanPlan plan) throws StoreException {
        List<KeysetRowSource> sources = new ArrayList<>();
        List<AutoCloseable> owned = new ArrayList<>();
        try {
            // A reader connection of its own: scans must not queue behind the writer, and WAL lets
            // them run concurrently.
            Connection hotReader = DriverManager.getConnection(jdbcUrl(directory.resolve("hot.db")));
            SqliteSchema.applyReadPragmas(hotReader);
            owned.add(hotReader);
            sources.add(new KeysetRowSource(hotReader, "hot_event", 0L, plan));

            for (ShardRef shard : liveShards(plan.fromMillis(), plan.toMillis())) {
                Connection reader = DriverManager.getConnection(jdbcUrl(shardDirectory.resolve(shard.file())));
                SqliteSchema.applyReadPragmas(reader);
                owned.add(reader);
                sources.add(new KeysetRowSource(reader, "ev", shard.baseMillis(), plan));
            }
            return new MergingCursor(plan, sources, owned);
        } catch (SQLException e) {
            owned.forEach(SqliteEventStore::closeQuietly);
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not open a scan: " + e.getMessage(), e);
        }
    }

    private List<ShardRef> liveShards(long fromMillis, long toMillis) throws SQLException {
        List<ShardRef> shards = new ArrayList<>();
        try (PreparedStatement statement = writer.prepareStatement(
                "SELECT file, base_ts FROM shard WHERE state = ? AND max_ts >= ? AND min_ts < ? ORDER BY min_ts")) {
            statement.setInt(1, STATE_LIVE);
            statement.setLong(2, fromMillis);
            statement.setLong(3, toMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shards.add(new ShardRef(rows.getString(1), rows.getLong(2)));
                }
            }
        }
        return shards;
    }

    @Override
    public void recordGap(GapRecord gap) throws StoreException {
        try (PreparedStatement insert = writer.prepareStatement(
                "INSERT INTO gap(from_ts, to_ts, reason, dropped, detail) VALUES (?,?,?,?,?)")) {
            insert.setLong(1, gap.fromMillis());
            insert.setLong(2, gap.toMillis());
            insert.setInt(3, gap.reason().id());
            insert.setLong(4, gap.droppedCount());
            insert.setString(5, gap.detail());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not record a gap: " + e.getMessage(), e);
        }
    }

    @Override
    public List<GapRecord> gapsBetween(long fromMillis, long toMillis) throws StoreException {
        List<GapRecord> gaps = new ArrayList<>();
        try (PreparedStatement statement = writer.prepareStatement(
                "SELECT from_ts, to_ts, reason, dropped, detail FROM gap WHERE from_ts <= ? AND to_ts >= ?"
                        + " ORDER BY from_ts")) {
            statement.setLong(1, toMillis);
            statement.setLong(2, fromMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    gaps.add(new GapRecord(
                            rows.getLong(1),
                            rows.getLong(2),
                            GapRecord.Reason.byId(rows.getInt(3)),
                            rows.getLong(4),
                            rows.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not read gaps: " + e.getMessage(), e);
        }
        return gaps;
    }

    @Override
    public StoreStats stats() throws StoreException {
        try (Statement statement = writer.createStatement()) {
            long hotRows;
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + HOT_SCHEMA + ".hot_event")) {
                rows.next();
                hotRows = rows.getLong(1);
            }
            long sealedRows;
            int shardCount;
            long shardBytes;
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COALESCE(SUM(row_count),0), COUNT(*), COALESCE(SUM(bytes),0) FROM shard WHERE state = 0")) {
                rows.next();
                sealedRows = rows.getLong(1);
                shardCount = rows.getInt(2);
                shardBytes = rows.getLong(3);
            }
            int gapCount;
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM gap")) {
                rows.next();
                gapCount = rows.getInt(1);
            }
            long total =
                    shardBytes + fileSize(directory.resolve("hot.db")) + fileSize(directory.resolve("manifest.db"));
            return new StoreStats(hotRows, sealedRows, shardCount, total, gapCount, appliedLsn);
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not read stats: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws StoreException {
        StoreException failure = null;
        try {
            try (Statement statement = writer.createStatement()) {
                // Fold the write-ahead log back into the databases so that a restart has less to do.
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            writer.close();
        } catch (SQLException e) {
            failure = new StoreException(
                    StoreException.Reason.INTERNAL, "Closing the store failed: " + e.getMessage(), e);
        }
        closeQuietly(lock, lockChannel);
        if (failure != null) {
            throw failure;
        }
    }

    private @Nullable String readMeta(String key) throws SQLException {
        try (PreparedStatement statement = writer.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private void writeMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = writer.prepareStatement(
                "INSERT INTO meta(k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private void rollbackQuietly() {
        try {
            writer.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private void setAutoCommitQuietly() {
        try {
            writer.setAutoCommit(true);
        } catch (SQLException ignored) {
            // Same.
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String jdbcUrl(Path path) {
        return "jdbc:sqlite:" + path.toAbsolutePath();
    }

    private static void closeQuietly(@Nullable AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            closeQuietly(closeable);
        }
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing useful to do while unwinding.
        }
    }

    /** Where a sealed shard lives and what its timestamps are relative to. */
    private record ShardRef(String file, long baseMillis) {}

    /** Exposed for tests and for {@code /trace status}: the position a scan would resume from. */
    public static CursorPosition positionOf(long chunkKey, long timestamp, int sequence) {
        return new CursorPosition(chunkKey, timestamp, sequence);
    }
}
