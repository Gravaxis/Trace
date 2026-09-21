/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MaintenanceBudget;
import in.gravaxis.trace.storage.MaintenanceOperation;
import in.gravaxis.trace.storage.MaintenanceResult;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.OperationProgress;
import in.gravaxis.trace.storage.OperationState;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.RollbackOperation;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.StoreStats;
import in.gravaxis.trace.storage.VerificationResult;
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
 * transaction within the manifest database, so there is no window in which a
 * row is in both places or in neither. The shard file itself is written and flushed before that
 * transaction begins; if the server dies in between, the file is an orphan with no manifest row and
 * is deleted on the next start.
 *
 * <p>Every method that touches the connection is {@code synchronized}. One JDBC connection cannot
 * serve two threads at once, and this store already has several: the consumer thread appends and
 * seals, a rollback reads gaps and opens scans from the async scheduler, and a command thread asks
 * for statistics. Without the lock an {@code UPDATE} issued from one of them lands inside whatever
 * transaction another has open, and {@code last_insert_rowid()} — which is per connection, not per
 * statement — can return a row somebody else inserted. A scan holds the lock only while it chooses
 * its shards; the cursor it returns reads through connections of its own and blocks nobody.
 *
 * <p>One writer per data directory, enforced with a lock file. A network wanting shared history
 * runs an external tier — the build spec is explicit that this belongs on page one of the
 * documentation, not in a footnote.
 */
public final class SqliteEventStore implements EventStore {

    private static final String HOT_SCHEMA = "main";
    private static final String META_APPLIED_LSN = "applied_lsn";
    private static final String META_FORMAT = "format_version";
    private static final int STATE_LIVE = 0;

    private static final String OPERATION_COLUMNS = """
            SELECT id, run_id, world, min_x, min_y, min_z, max_x, max_y, max_z, from_ts, to_ts, actor,
                   state, cursor_chunk, cursor_ts, cursor_seq, applied, already, mismatched, scanned,
                   chunks, started_at, updated_at
            FROM rollback_op""";

    /** No frame has been applied yet. Journal positions start at zero, so this cannot be zero. */
    private static final long NOTHING_APPLIED = -1L;

    private final Path directory;
    private final Path shardDirectory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection writer;

    private long appliedLsn;
    private int activeScans;
    private @Nullable ShardWork work;
    private @Nullable MaintenanceOperation workOperation;
    private long lastVerified;
    private @Nullable MaintenanceControl maintenance;

    private void checkMaintenance() throws SQLException {
        if (maintenance != null) maintenance.check();
    }

    private void installMaintenance(Connection connection) throws SQLException {
        if (maintenance != null) maintenance.install(connection);
    }

    private void clearMaintenanceHandler() {
        try {
            org.sqlite.ProgressHandler.clearHandler(writer);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear maintenance callback", e);
        }
    }

    private java.util.function.Consumer<String> maintenanceProbe = phase -> {};

    void maintenanceProbe(java.util.function.Consumer<String> probe) {
        maintenanceProbe = probe;
    }

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
        return open(directory, phase -> {});
    }

    static SqliteEventStore open(Path directory, java.util.function.Consumer<String> migrationProbe)
            throws StoreException {
        FileChannel lockChannel = null;
        FileLock lock = null;
        Connection connection = null;
        try {
            Files.createDirectories(directory.resolve("shards"));
            lockChannel =
                    FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw alreadyOpen(directory, null);
            }

            connection = DriverManager.getConnection(jdbcUrl(directory.resolve("manifest.db")));
            int oldFormat = inspectFormat(connection);
            SqliteSchema.applyWritePragmas(connection);
            SqliteSchema.createManifest(connection);
            migrateHot(connection, directory, oldFormat, migrationProbe);

            SqliteEventStore store = new SqliteEventStore(directory, lockChannel, lock, connection);
            store.initialiseMeta();
            store.deleteOrphanShards();
            store.sweepRetired();
            return store;
        } catch (StoreException e) {
            closeQuietly(connection, lock, lockChannel);
            throw e;
        } catch (SQLException e) {
            closeQuietly(connection, lock, lockChannel);
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not open the store: " + e.getMessage(), e);
        } catch (OverlappingFileLockException e) {
            closeQuietly(connection, lock, lockChannel);
            throw alreadyOpen(directory, e);
        } catch (IOException e) {
            closeQuietly(connection, lock, lockChannel);
            throw new StoreException(
                    StoreException.Reason.DISK, "Could not open " + directory + ": " + e.getMessage(), e);
        }
    }

    private static int inspectFormat(Connection connection) throws SQLException, StoreException {
        try (Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery(
                        "SELECT count(*) FROM sqlite_schema WHERE name='meta' AND type='table'")) {
            tables.next();
            if (tables.getInt(1) == 0) return 0;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT v FROM meta WHERE k='format_version'")) {
            if (!rows.next()) return 0;
            int version;
            try {
                version = Integer.parseInt(rows.getString(1));
            } catch (NumberFormatException e) {
                throw new StoreException(StoreException.Reason.CORRUPT, "Invalid storage format", e);
            }
            if (version < 0 || version > SqliteSchema.FORMAT_VERSION) {
                throw new StoreException(
                        StoreException.Reason.CORRUPT,
                        "Unsupported storage format " + version + "; refusing to modify it");
            }
            return version;
        }
    }

    private static void migrateHot(
            Connection connection, Path directory, int oldFormat, java.util.function.Consumer<String> probe)
            throws SQLException {
        boolean legacy = oldFormat < 3 && Files.isRegularFile(directory.resolve("hot.db"));
        if (legacy) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ATTACH DATABASE '"
                        + directory.resolve("hot.db").toUri().toString().replace("'", "''") + "?mode=ro' AS legacy");
            }
        }
        connection.setAutoCommit(false);
        try {
            SqliteSchema.createHot(connection, HOT_SCHEMA);
            try (Statement statement = connection.createStatement()) {
                if (legacy) statement.executeUpdate("INSERT INTO main.hot_event SELECT * FROM legacy.hot_event");
                if (legacy) probe.accept("migration.copied");
                statement.executeUpdate(
                        "INSERT INTO meta(k,v) VALUES('format_version','3') ON CONFLICT(k) DO UPDATE SET v=excluded.v");
            }
            connection.commit();
            if (legacy) probe.accept("migration.committed");
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
            if (legacy) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DETACH DATABASE legacy");
                }
            }
        }
        // Keep the legacy file as migration evidence. Format 3 never reads it again.
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
        if (format == null || Integer.parseInt(format) < SqliteSchema.FORMAT_VERSION) {
            // Rewritten, not just written-when-absent. The tables above are created unconditionally,
            // so an older directory silently gains them; if the recorded version stayed behind, an
            // older Trace would keep opening the directory happily and would not see, for instance,
            // that a rollback was left half-applied. Upgrading is one-way, which is what the message
            // below already promises.
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
    public synchronized void append(RecordBatch batch, long journalPosition) throws StoreException {
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
            List<Exclusion> exclusions = exclusions();
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
                    int actor = EventRecords.actorId(actorTime, metadata);
                    if (exclusions.stream().anyMatch(e -> e.matches(actor, timestamp))) continue;

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
    public synchronized long appliedLsn() {
        return appliedLsn;
    }

    @Override
    public synchronized long seal() throws StoreException {
        discardWork();
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
        return writeShard(shardFile, baseMillis, maxRowId, HOT_SCHEMA + ".hot_event");
    }

    private long writeShard(Path shardFile, long baseMillis, long maxRowId, String table)
            throws SQLException, IOException {
        long written = 0;
        RowDigest expected = new RowDigest();
        try (Connection shard = DriverManager.getConnection(jdbcUrl(shardFile))) {
            installMaintenance(shard);
            SqliteSchema.applySealPragmas(shard);
            SqliteSchema.createShard(shard);
            shard.setAutoCommit(false);
            try (PreparedStatement insert = shard.prepareStatement(
                            "INSERT INTO ev(w,c,k,p,b,a,actor,cause,kind) VALUES (?,?,?,?,?,?,?,?,?)");
                    PreparedStatement select =
                            writer.prepareStatement("SELECT w, c, k, p, b, a, actor, cause, kind FROM " + table
                                    + " WHERE rowid <= ? ORDER BY w, c, k")) {
                // Sorted once, here, and bulk-loaded in primary-key order: appends land in the
                // rightmost leaf, so pages end up nearly full instead of half empty.
                select.setLong(1, maxRowId);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        checkMaintenance();
                        expected.add(
                                rows.getInt(1),
                                rows.getLong(2),
                                ShardKeys.timestampOf(0, rows.getLong(3)),
                                ShardKeys.sequenceOf(rows.getLong(3)),
                                rows.getInt(4),
                                rows.getInt(5),
                                rows.getInt(6),
                                rows.getInt(7),
                                rows.getInt(8),
                                rows.getInt(9));
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
        String intended = expected.finish();
        try (Connection check = readShard(shardFile)) {
            if (!intended.equals(RowDigest.read(check, baseMillis)))
                throw new SQLException("Seal content verification failed");
        }
        return written;
    }

    private void publishShard(
            long shardId, String fileName, long minTs, long maxTs, long rowCount, long bytes, long maxRowId)
            throws SQLException, StoreException {
        try {
            writer.setAutoCommit(false);
            String digest;
            try (Connection check = readShard(shardDirectory.resolve(fileName))) {
                digest = RowDigest.read(check, minTs);
            }
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
            saveDigest(shardId, digest);
            try (PreparedStatement delete =
                    writer.prepareStatement("DELETE FROM " + HOT_SCHEMA + ".hot_event WHERE rowid <= ?")) {
                delete.setLong(1, maxRowId);
                delete.executeUpdate();
            }
            // One database: the manifest and hot rows share the same WAL commit.
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
    public synchronized MutationCursor scan(ScanPlan plan) throws StoreException {
        List<KeysetRowSource> sources = new ArrayList<>();
        List<AutoCloseable> owned = new ArrayList<>();
        try {
            for (GapRecord gap : gapsBetween(plan.fromMillis(), plan.toMillis())) {
                if (gap.reason() == GapRecord.Reason.QUARANTINE
                        || gap.reason() == GapRecord.Reason.EXPIRED
                        || gap.reason() == GapRecord.Reason.PURGED) {
                    throw new StoreException(StoreException.Reason.CORRUPT, "History unavailable: " + gap.reason());
                }
            }
            // A reader connection of its own: scans must not queue behind the writer, and WAL lets
            // them run concurrently.
            Connection hotReader = DriverManager.getConnection(jdbcUrl(directory.resolve("manifest.db")));
            SqliteSchema.applyReadPragmas(hotReader);
            hotReader.setAutoCommit(false);
            // Pin a snapshot before releasing the store monitor, including empty hot tables.
            try (Statement statement = hotReader.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*) FROM meta")) {
                rows.next();
            }
            owned.add(hotReader);
            sources.add(new KeysetRowSource(hotReader, "hot_event", 0L, plan));

            for (ShardRef shard : liveShards(plan.fromMillis(), plan.toMillis())) {
                Path file = shardDirectory.resolve(shard.file());
                if (!Files.isRegularFile(file)) {
                    quarantineShard(shard.id(), "missing shard file");
                    throw new StoreException(StoreException.Reason.CORRUPT, "Missing live shard " + shard.id());
                }
                Connection reader = readShard(file);
                owned.add(reader);
                sources.add(new KeysetRowSource(reader, "ev", shard.baseMillis(), plan));
            }
            activeScans++;
            owned.add(this::releaseScan);
            return new MergingCursor(plan, sources, owned);
        } catch (StoreException e) {
            owned.forEach(SqliteEventStore::closeQuietly);
            throw e;
        } catch (SQLException e) {
            owned.forEach(SqliteEventStore::closeQuietly);
            throw new StoreException(StoreException.Reason.INTERNAL, "Could not open a scan: " + e.getMessage(), e);
        }
    }

    private List<ShardRef> liveShards(long fromMillis, long toMillis) throws SQLException {
        List<ShardRef> shards = new ArrayList<>();
        try (PreparedStatement statement = writer.prepareStatement(
                "SELECT id, file, base_ts, min_ts, max_ts, row_count FROM shard WHERE state = ? AND max_ts >= ? AND min_ts < ? ORDER BY min_ts")) {
            statement.setInt(1, STATE_LIVE);
            statement.setLong(2, fromMillis);
            statement.setLong(3, toMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shards.add(new ShardRef(
                            rows.getLong(1),
                            rows.getString(2),
                            rows.getLong(3),
                            rows.getLong(4),
                            rows.getLong(5),
                            rows.getLong(6)));
                }
            }
        }
        return shards;
    }

    @Override
    public synchronized void recordGap(GapRecord gap) throws StoreException {
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
    public synchronized List<GapRecord> gapsBetween(long fromMillis, long toMillis) throws StoreException {
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
    public synchronized long beginOperation(RollbackOperation operation) throws StoreException {
        try (PreparedStatement insert = writer.prepareStatement("""
                INSERT INTO rollback_op(
                  run_id, world, min_x, min_y, min_z, max_x, max_y, max_z, from_ts, to_ts, actor, state,
                  cursor_chunk, cursor_ts, cursor_seq, applied, already, mismatched, scanned, chunks,
                  started_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,?,?,?,?,?,?,?)""")) {
            BlockBox box = operation.box();
            OperationProgress progress = operation.progress();
            int index = 1;
            insert.setString(index++, operation.runId());
            insert.setInt(index++, operation.worldId());
            insert.setInt(index++, box.minX());
            insert.setInt(index++, box.minY());
            insert.setInt(index++, box.minZ());
            insert.setInt(index++, box.maxX());
            insert.setInt(index++, box.maxY());
            insert.setInt(index++, box.maxZ());
            insert.setLong(index++, operation.fromMillis());
            insert.setLong(index++, operation.toMillis());
            insert.setInt(index++, operation.actorId());
            insert.setInt(index++, operation.state().id());
            insert.setLong(index++, progress.applied());
            insert.setLong(index++, progress.already());
            insert.setLong(index++, progress.mismatched());
            insert.setLong(index++, progress.scanned());
            insert.setLong(index++, progress.chunks());
            insert.setLong(index++, operation.startedAtMillis());
            insert.setLong(index, operation.updatedAtMillis());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new StoreException(
                            StoreException.Reason.INTERNAL, "The store did not give the operation an id");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Could not record the rollback: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void checkpointOperation(
            long operationId, @Nullable CursorPosition cursor, OperationProgress progress) throws StoreException {
        // A null cursor leaves the stored one alone rather than clearing it. That is what a caller
        // means when it has counters to record but cannot promise the work up to here is complete -
        // a chunk it could not apply, for instance.
        String sql = cursor == null
                ? "UPDATE rollback_op SET applied=?, already=?, mismatched=?, scanned=?, chunks=?, updated_at=?"
                        + " WHERE id = ?"
                : "UPDATE rollback_op SET applied=?, already=?, mismatched=?, scanned=?, chunks=?, updated_at=?,"
                        + " cursor_chunk=?, cursor_ts=?, cursor_seq=? WHERE id = ?";
        try (PreparedStatement update = writer.prepareStatement(sql)) {
            int index = 1;
            index = bindProgress(update, index, progress);
            update.setLong(index++, System.currentTimeMillis());
            if (cursor != null) {
                update.setLong(index++, cursor.chunkKey());
                update.setLong(index++, cursor.timestamp());
                update.setInt(index++, cursor.sequence());
            }
            update.setLong(index, operationId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Could not checkpoint the rollback: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void finishOperation(long operationId, OperationState state, OperationProgress progress)
            throws StoreException {
        try (PreparedStatement update = writer.prepareStatement(
                "UPDATE rollback_op SET applied=?, already=?, mismatched=?, scanned=?, chunks=?, updated_at=?,"
                        + " state=? WHERE id = ?")) {
            int index = 1;
            index = bindProgress(update, index, progress);
            update.setLong(index++, System.currentTimeMillis());
            update.setInt(index++, state.id());
            update.setLong(index, operationId);
            update.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Could not finish the rollback: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized @Nullable RollbackOperation operation(long operationId) throws StoreException {
        try (PreparedStatement statement = writer.prepareStatement(OPERATION_COLUMNS + " WHERE id = ?")) {
            statement.setLong(1, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readOperation(rows) : null;
            }
        } catch (SQLException e) {
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Could not read the rollback: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<RollbackOperation> unfinishedOperations() throws StoreException {
        List<RollbackOperation> operations = new ArrayList<>();
        try (PreparedStatement statement =
                writer.prepareStatement(OPERATION_COLUMNS + " WHERE state <> ? ORDER BY id")) {
            statement.setInt(1, OperationState.DONE.id());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    operations.add(readOperation(rows));
                }
            }
        } catch (SQLException e) {
            throw new StoreException(
                    StoreException.Reason.INTERNAL, "Could not read the rollbacks: " + e.getMessage(), e);
        }
        return operations;
    }

    private static int bindProgress(PreparedStatement statement, int from, OperationProgress progress)
            throws SQLException {
        int index = from;
        statement.setLong(index++, progress.applied());
        statement.setLong(index++, progress.already());
        statement.setLong(index++, progress.mismatched());
        statement.setLong(index++, progress.scanned());
        statement.setLong(index++, progress.chunks());
        return index;
    }

    private static RollbackOperation readOperation(ResultSet rows) throws SQLException {
        long cursorChunk = rows.getLong("cursor_chunk");
        CursorPosition cursor = rows.wasNull()
                ? null
                : new CursorPosition(cursorChunk, rows.getLong("cursor_ts"), rows.getInt("cursor_seq"));
        return new RollbackOperation(
                rows.getLong("id"),
                rows.getString("run_id"),
                rows.getInt("world"),
                new BlockBox(
                        rows.getInt("min_x"),
                        rows.getInt("min_y"),
                        rows.getInt("min_z"),
                        rows.getInt("max_x"),
                        rows.getInt("max_y"),
                        rows.getInt("max_z")),
                rows.getLong("from_ts"),
                rows.getLong("to_ts"),
                rows.getInt("actor"),
                OperationState.byId(rows.getInt("state")),
                cursor,
                new OperationProgress(
                        rows.getLong("applied"),
                        rows.getLong("already"),
                        rows.getLong("mismatched"),
                        rows.getLong("scanned"),
                        rows.getLong("chunks")),
                rows.getLong("started_at"),
                rows.getLong("updated_at"));
    }

    @Override
    public synchronized StoreStats stats() throws StoreException {
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
    public synchronized void close() throws StoreException {
        discardWork();
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
    private record ShardRef(long id, String file, long baseMillis, long minMillis, long maxMillis, long rows) {}

    private synchronized void releaseScan() {
        activeScans--;
    }

    @Override
    public synchronized void rewindOperation(long id, String runId) throws StoreException {
        try (var s = writer.prepareStatement(
                "UPDATE rollback_op SET run_id=?,cursor_chunk=NULL,cursor_ts=NULL,cursor_seq=NULL,applied=0,already=0,mismatched=0,scanned=0,chunks=0 WHERE id=? AND state<>?")) {
            s.setString(1, runId);
            s.setLong(2, id);
            s.setInt(3, OperationState.DONE.id());
            s.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Crash resume rewind failed", e);
        }
    }

    private void requireNoScans() throws StoreException {
        if (activeScans != 0)
            throw new StoreException(StoreException.Reason.CONTENDED, "Maintenance deferred while scans are open");
    }

    private Connection readShard(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro");
        try {
            SqliteSchema.applyReadPragmas(connection);
            installMaintenance(connection);
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private void saveDigest(long id, String digest) throws SQLException {
        try (var s = writer.prepareStatement("INSERT INTO shard_digest(id,digest) VALUES(?,?)")) {
            s.setLong(1, id);
            s.setString(2, digest);
            s.executeUpdate();
        }
    }

    private @Nullable String expectedDigest(long id) throws SQLException {
        try (var s = writer.prepareStatement("SELECT digest FROM shard_digest WHERE id=?")) {
            s.setLong(1, id);
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    @Override
    public synchronized VerificationResult verify() throws StoreException {
        requireNoScans();
        int verified = 0, unverified = 0, quarantined = 0;
        try {
            for (ShardRef shard : liveShards(Long.MIN_VALUE, Long.MAX_VALUE)) {
                boolean healthy;
                String expected = expectedDigest(shard.id());
                try (Connection reader = readShard(shardDirectory.resolve(shard.file()));
                        var s = reader.createStatement()) {
                    try (var r = s.executeQuery("PRAGMA integrity_check")) {
                        healthy = r.next() && "ok".equals(r.getString(1)) && !r.next();
                    }
                    if (healthy && expected != null)
                        healthy = expected.equals(RowDigest.read(reader, shard.baseMillis()));
                    try (var r = s.executeQuery("SELECT count(*),min(k),max(k) FROM ev")) {
                        healthy &= r.next() && r.getLong(1) == shard.rows();
                        if (shard.rows() > 0)
                            healthy &= ShardKeys.timestampOf(shard.baseMillis(), r.getLong(2)) == shard.minMillis()
                                    && ShardKeys.timestampOf(shard.baseMillis(), r.getLong(3)) == shard.maxMillis();
                    }
                } catch (SQLException e) {
                    healthy = false;
                }
                if (!healthy) {
                    quarantineShard(shard.id(), "verification failed");
                    quarantined++;
                } else if (expected == null) unverified++;
                else verified++;
            }
            int blobsVerified = 0, blobsCorrupt = 0;
            List<String> blobs = new ArrayList<>();
            try (var s = writer.createStatement();
                    var r = s.executeQuery("SELECT id FROM blob")) {
                while (r.next()) blobs.add(r.getString(1));
            }
            for (String id : blobs) {
                try {
                    readBlob(id);
                    blobsVerified++;
                } catch (StoreException failure) {
                    blobsCorrupt++;
                    writer.setAutoCommit(false);
                    try {
                        int marked;
                        try (var s = writer.prepareStatement("INSERT INTO blob_bad VALUES(?) ON CONFLICT DO NOTHING")) {
                            s.setString(1, id);
                            marked = s.executeUpdate();
                        }
                        if (marked > 0) {
                            try (var s = writer.prepareStatement(
                                    "SELECT min(ts),max(ts),count(*) FROM blob_ref WHERE blob_id=?")) {
                                s.setString(1, id);
                                try (var r = s.executeQuery()) {
                                    r.next();
                                    if (r.getLong(3) > 0)
                                        recordGap(new GapRecord(
                                                r.getLong(1),
                                                r.getLong(2),
                                                GapRecord.Reason.QUARANTINE,
                                                r.getLong(3),
                                                "corrupt blob references"));
                                }
                            }
                            audit("BLOB_CORRUPT", 0, 1);
                        }
                        writer.commit();
                    } catch (SQLException | StoreException e) {
                        rollbackQuietly();
                        throw new StoreException(StoreException.Reason.INTERNAL, "Blob quarantine failed", e);
                    } finally {
                        setAutoCommitQuietly();
                    }
                }
            }
            return new VerificationResult(verified, unverified, quarantined, blobsVerified, blobsCorrupt);
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Verification failed", e);
        }
    }

    @Override
    public synchronized void quarantineShard(long id, String detail) throws StoreException {
        discardWork();
        requireNoScans();
        try {
            ShardRef target = null;
            for (ShardRef shard : liveShards(Long.MIN_VALUE, Long.MAX_VALUE)) if (shard.id() == id) target = shard;
            if (target == null) return;
            writer.setAutoCommit(false);
            try (var s = writer.prepareStatement("UPDATE shard SET state=2 WHERE id=? AND state=0")) {
                s.setLong(1, id);
                s.executeUpdate();
            }
            recordGap(new GapRecord(
                    target.minMillis(), target.maxMillis(), GapRecord.Reason.QUARANTINE, target.rows(), detail));
            audit("QUARANTINE", id, target.rows());
            writer.commit();
        } catch (SQLException | StoreException e) {
            rollbackQuietly();
            throw new StoreException(StoreException.Reason.INTERNAL, "Quarantine transaction failed", e);
        } finally {
            setAutoCommitQuietly();
        }
    }

    private void audit(String action, long shardId, long affected) throws SQLException {
        try (var s = writer.prepareStatement(
                "INSERT INTO maintenance_audit(action,shard_id,affected,created_at) VALUES(?,?,?,?)")) {
            s.setString(1, action);
            s.setLong(2, shardId);
            s.setLong(3, affected);
            s.setLong(4, System.currentTimeMillis());
            s.executeUpdate();
        }
    }

    /** Physical deletion is delayed until every old scan has released its connections. */
    public synchronized void sweepRetired() throws StoreException {
        if (activeScans != 0) return;
        int limit = maintenance == null ? Integer.MAX_VALUE : maintenance.budget.maxShards();
        List<RetiredFile> retired = new ArrayList<>();
        try (var s = writer.prepareStatement("SELECT id,file FROM shard WHERE state=1 ORDER BY id LIMIT ?")) {
            s.setInt(1, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) retired.add(new RetiredFile(r.getLong(1), r.getString(2)));
            }
            for (RetiredFile file : retired) {
                Files.deleteIfExists(shardDirectory.resolve(file.name()));
                // A kill between unlink and this marker retries the missing file safely.
                // Remembering completion prevents each bounded pass revisiting the same prefix.
                try (var mark = writer.prepareStatement("UPDATE shard SET state=3 WHERE id=? AND state=1")) {
                    mark.setLong(1, file.id());
                    mark.executeUpdate();
                }
            }
        } catch (SQLException | IOException e) {
            throw new StoreException(StoreException.Reason.DISK, "Retired shard sweep failed", e);
        }
    }

    private record RetiredFile(long id, String name) {}

    private void discardWork() throws StoreException {
        ShardWork pending = work;
        work = null;
        workOperation = null;
        if (pending != null) {
            try {
                pending.close();
            } catch (SQLException | IOException e) {
                throw new StoreException(StoreException.Reason.DISK, "Discarding unpublished maintenance failed", e);
            }
        }
    }

    @Override
    public synchronized MaintenanceResult maintain(
            MaintenanceOperation operation, MaintenanceBudget budget, long cutoff) throws StoreException {
        if (budget.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
            discardWork();
            return new MaintenanceResult(MaintenanceResult.State.CANCELLED, 0);
        }
        if (workOperation != null && workOperation != operation)
            throw new StoreException(
                    StoreException.Reason.CONTENDED, "Finish or cancel the active maintenance phase first");
        if (operation == MaintenanceOperation.CHECKPOINT) return checkpointMaintenance();
        if ((operation == MaintenanceOperation.RETAIN || operation == MaintenanceOperation.VERIFY) && activeScans > 0)
            return new MaintenanceResult(MaintenanceResult.State.DEFERRED, 0);
        try {
            ShardWork pending = work;
            if (pending == null) {
                List<ShardWork.Source> inputs = new ArrayList<>();
                long hotEnd = 0;
                if (operation == MaintenanceOperation.SEAL) {
                    // Freeze a bounded prefix without holding a manifest read transaction between calls.
                    try (var s = writer.prepareStatement("SELECT rowid FROM hot_event ORDER BY rowid LIMIT ?")) {
                        s.setLong(1, Math.min(4096, budget.maxInputRows()));
                        try (var r = s.executeQuery()) {
                            while (r.next()) hotEnd = r.getLong(1);
                        }
                    }
                    if (hotEnd == 0) return new MaintenanceResult(MaintenanceResult.State.NO_WORK, 0);
                } else {
                    String predicate = operation == MaintenanceOperation.VERIFY
                            ? " AND id>?"
                            : operation == MaintenanceOperation.RETAIN ? " AND min_ts<?" : "";
                    try (var s = writer.prepareStatement(
                            "SELECT id,file,base_ts,row_count,min_ts,max_ts FROM shard WHERE state=0" + predicate
                                    + " ORDER BY id LIMIT ?")) {
                        int param = 1;
                        if (operation == MaintenanceOperation.VERIFY) s.setLong(param++, lastVerified);
                        if (operation == MaintenanceOperation.RETAIN) s.setLong(param++, cutoff);
                        s.setInt(param, operation == MaintenanceOperation.COMPACT ? 2 : 1);
                        try (var r = s.executeQuery()) {
                            while (r.next())
                                inputs.add(new ShardWork.Source(
                                        r.getLong(1),
                                        shardDirectory.resolve(r.getString(2)),
                                        r.getLong(3),
                                        r.getLong(4),
                                        r.getLong(5),
                                        r.getLong(6),
                                        expectedDigest(r.getLong(1))));
                        }
                    }
                    if (inputs.isEmpty() || (operation == MaintenanceOperation.COMPACT && inputs.size() < 2)) {
                        if (operation == MaintenanceOperation.VERIFY) lastVerified = 0;
                        sweepBounded(budget);
                        return new MaintenanceResult(MaintenanceResult.State.NO_WORK, 0);
                    }
                }
                pending = new ShardWork(
                        inputs,
                        shardDirectory.resolve("work-" + java.util.UUID.randomUUID() + ".db"),
                        operation == MaintenanceOperation.VERIFY,
                        hotEnd,
                        operation == MaintenanceOperation.RETAIN ? cutoff : Long.MIN_VALUE);
                work = pending;
                workOperation = operation;
            }
            long processed = pending.step(writer, budget);
            maintenanceProbe.accept("incremental.step");
            if (budget.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                discardWork();
                return new MaintenanceResult(MaintenanceResult.State.CANCELLED, 0);
            }
            if (!pending.done) return new MaintenanceResult(MaintenanceResult.State.PROGRESSED, processed);
            if (pending.verifyOnly) {
                lastVerified = pending.sources.getFirst().id();
                boolean trusted = pending.sources.getFirst().digest() != null;
                discardWork();
                return new MaintenanceResult(
                        trusted ? MaintenanceResult.State.COMPLETED : MaintenanceResult.State.UNVERIFIED, processed);
            }
            maintenanceProbe.accept("incremental.output-forced");
            if (budget.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
                discardWork();
                return new MaintenanceResult(MaintenanceResult.State.CANCELLED, 0);
            }
            publishWork(pending, operation);
            long rows = operation == MaintenanceOperation.RETAIN ? pending.removed : pending.copied;
            discardWork();
            sweepBounded(budget);
            return new MaintenanceResult(MaintenanceResult.State.COMPLETED, rows);
        } catch (SQLException | IOException e) {
            ShardWork pending = work;
            long damaged = operation == MaintenanceOperation.VERIFY && pending != null ? pending.failingShard : 0;
            discardWork();
            if (damaged != 0) {
                lastVerified = damaged;
                quarantineShard(damaged, "scheduled content verification failed");
                return new MaintenanceResult(MaintenanceResult.State.QUARANTINED, 0);
            }
            throw new StoreException(StoreException.Reason.INTERNAL, "Incremental maintenance failed", e);
        } catch (StoreException e) {
            discardWork();
            throw e;
        }
    }

    private void sweepBounded(MaintenanceBudget budget) throws StoreException {
        maintenance = new MaintenanceControl(budget);
        try {
            sweepRetired();
        } finally {
            maintenance = null;
        }
    }

    private MaintenanceResult checkpointMaintenance() throws StoreException {
        try (var s = writer.createStatement()) {
            s.execute("PRAGMA busy_timeout=0");
            try (var r = s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                if (!r.next()) throw new SQLException("Missing checkpoint status");
                return new MaintenanceResult(
                        r.getInt(1) == 0 ? MaintenanceResult.State.COMPLETED : MaintenanceResult.State.DEFERRED, 0);
            } finally {
                s.execute("PRAGMA busy_timeout=5000");
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.DISK, "Checkpoint failed", e);
        }
    }

    private void publishWork(ShardWork pending, MaintenanceOperation operation)
            throws SQLException, IOException, StoreException {
        writer.setAutoCommit(false);
        try {
            for (var source : pending.sources) {
                try (var s = writer.prepareStatement("UPDATE shard SET state=1 WHERE id=? AND state=0")) {
                    s.setLong(1, source.id());
                    if (s.executeUpdate() != 1) throw new SQLException("Maintenance input changed before publication");
                }
            }
            long id = nextShardId();
            if (pending.copied > 0) {
                try (var s = writer.prepareStatement("INSERT INTO shard VALUES(?,?,?,?,?,?,?,?,?)")) {
                    s.setLong(1, id);
                    s.setString(2, pending.output.getFileName().toString());
                    s.setLong(3, 0);
                    s.setLong(4, pending.min);
                    s.setLong(5, pending.max);
                    s.setLong(6, pending.copied);
                    s.setLong(7, Files.size(pending.output));
                    s.setInt(8, 0);
                    s.setLong(9, System.currentTimeMillis());
                    s.executeUpdate();
                }
                saveDigest(id, pending.content);
            }
            if (pending.hotEnd > 0) {
                try (var s = writer.prepareStatement("DELETE FROM hot_event WHERE rowid<=?")) {
                    s.setLong(1, pending.hotEnd);
                    if (s.executeUpdate() != pending.copied)
                        throw new SQLException("Hot prefix changed before publication");
                }
            }
            if (operation == MaintenanceOperation.RETAIN) {
                long from = pending.sources.getFirst().min();
                long to = pending.cutoff;
                // The exclusion also hides expired blob references. Reclaim them explicitly;
                // deleting an arbitrary number here would defeat bounded publication.
                try (var s = writer.prepareStatement("INSERT INTO exclusion(actor,from_ts,to_ts) VALUES(NULL,?,?)")) {
                    s.setLong(1, from);
                    s.setLong(2, to);
                    s.executeUpdate();
                }
                recordGap(
                        new GapRecord(from, to - 1, GapRecord.Reason.EXPIRED, pending.removed, "scheduled retention"));
            }
            audit(
                    "STEP_" + operation,
                    pending.copied > 0 ? id : 0,
                    operation == MaintenanceOperation.RETAIN ? pending.removed : pending.copied);
            writer.commit();
            if (pending.copied > 0) pending.published();
            maintenanceProbe.accept("incremental.committed");
        } catch (SQLException | StoreException | RuntimeException e) {
            rollbackQuietly();
            throw e;
        } finally {
            setAutoCommitQuietly();
        }
    }

    @Override
    public synchronized long compact() throws StoreException {
        return requireFinished(compact(MaintenanceBudget.defaults()));
    }

    @Override
    public synchronized MaintenanceResult compact(MaintenanceBudget budget) throws StoreException {
        return boundedRewrite(null, 0, 0, false, budget);
    }

    @Override
    public synchronized MaintenanceResult expireBefore(long cutoffMillis, MaintenanceBudget budget)
            throws StoreException {
        if (cutoffMillis == Long.MIN_VALUE) throw new IllegalArgumentException("Empty retention window");
        return boundedRewrite(null, Long.MIN_VALUE, cutoffMillis, true, budget);
    }

    private static long requireFinished(MaintenanceResult result) throws StoreException {
        if (result.state() == MaintenanceResult.State.DEFERRED || result.state() == MaintenanceResult.State.CANCELLED)
            throw new StoreException(StoreException.Reason.CONTENDED, "Maintenance " + result.state());
        return result.rows();
    }

    private MaintenanceResult boundedRewrite(
            @Nullable Integer actor, long from, long to, boolean remove, MaintenanceBudget budget)
            throws StoreException {
        discardWork();
        MaintenanceControl control = new MaintenanceControl(budget);
        maintenance = control;
        try {
            control.install(writer);
            control.check();
            if (remove && activeScans != 0) return new MaintenanceResult(MaintenanceResult.State.DEFERRED, 0);
            long rows = rewrite(actor, from, to, remove);
            control.install(writer);
            control.check();
            sweepRetired();
            return new MaintenanceResult(
                    rows == 0 ? MaintenanceResult.State.NO_WORK : MaintenanceResult.State.COMPLETED, rows);
        } catch (StoreException | SQLException e) {
            if (control.stopped != null && !control.published) return new MaintenanceResult(control.stopped, 0);
            if (e instanceof StoreException failure) throw failure;
            throw new StoreException(StoreException.Reason.INTERNAL, "Maintenance failed", e);
        } finally {
            clearMaintenanceHandler();
            maintenance = null;
        }
    }

    @Override
    public synchronized long expireBefore(long cutoffMillis) throws StoreException {
        return requireFinished(expireBefore(cutoffMillis, MaintenanceBudget.defaults()));
    }

    @Override
    public synchronized long purgeActor(int actorId, long fromMillis, long toMillis) throws StoreException {
        requireNoScans();
        if (fromMillis >= toMillis) throw new IllegalArgumentException("Empty or inverted purge window");
        return requireFinished(boundedRewrite(actorId, fromMillis, toMillis, true, MaintenanceBudget.defaults()));
    }

    private List<Exclusion> exclusions() throws SQLException {
        List<Exclusion> result = new ArrayList<>();
        try (var s = writer.createStatement();
                var r = s.executeQuery("SELECT actor,from_ts,to_ts FROM exclusion")) {
            while (r.next()) {
                int actor = r.getInt(1);
                boolean all = r.wasNull();
                result.add(new Exclusion(all, actor, r.getLong(2), r.getLong(3)));
            }
        }
        return result;
    }

    private record Exclusion(boolean allActors, int actor, long from, long to) {
        boolean matches(int value, long timestamp) {
            return (allActors || actor == value) && timestamp >= from && timestamp < to;
        }
    }

    private static String blobIdentity(int version, byte[] payload) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(
                    java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(version).array());
            return java.util.HexFormat.of().formatHex(digest.digest(payload));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public synchronized String putBlob(int version, byte[] payload) throws StoreException {
        if (version < 0 || payload.length > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Invalid blob version or payload limit exceeded");
        byte[] value = payload.clone();
        String id = blobIdentity(version, value);
        try {
            fullSynchronous();
            try (var s = writer.prepareStatement(
                    "INSERT INTO blob(id,version,payload) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING")) {
                s.setString(1, id);
                s.setInt(2, version);
                s.setBytes(3, value);
                s.executeUpdate();
            }
            if (!java.util.Arrays.equals(readBlob(id), value))
                throw new StoreException(StoreException.Reason.CORRUPT, "Blob identity collision");
            return id;
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.DISK, "Blob write failed", e);
        } finally {
            normalSynchronous();
        }
    }

    @Override
    public synchronized byte[] readBlob(String id) throws StoreException {
        try (var s = writer.prepareStatement(
                "SELECT version,payload,length(payload) FROM blob WHERE id=? AND NOT EXISTS(SELECT 1 FROM blob_bad WHERE blob_bad.id=blob.id)")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new StoreException(StoreException.Reason.CORRUPT, "Missing blob");
                if (r.getLong(3) > 16L * 1024 * 1024)
                    throw new StoreException(StoreException.Reason.CORRUPT, "Stored blob exceeds payload limit");
                byte[] value = r.getBytes(2);
                if (!id.equals(blobIdentity(r.getInt(1), value)))
                    throw new StoreException(StoreException.Reason.CORRUPT, "Blob checksum mismatch");
                return value;
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.CORRUPT, "Blob read failed", e);
        }
    }

    @Override
    public synchronized void attachBlob(int worldId, CursorPosition event, String blobId) throws StoreException {
        readBlob(blobId);
        try {
            Integer actor = findActor(writer, "main.hot_event", 0, worldId, event);
            if (actor == null) {
                for (ShardRef shard : liveShards(event.timestamp(), event.timestamp() + 1)) {
                    try (Connection reader = readShard(shardDirectory.resolve(shard.file()))) {
                        actor = findActor(reader, "ev", shard.baseMillis(), worldId, event);
                    }
                    if (actor != null) break;
                }
            }
            if (actor == null)
                throw new StoreException(StoreException.Reason.CORRUPT, "Cannot attach blob to a missing event");
            fullSynchronous();
            try (var s = writer.prepareStatement(
                    "INSERT INTO blob_ref(w,c,ts,seq,actor,blob_id) VALUES(?,?,?,?,?,?) ON CONFLICT(w,c,ts,seq) DO NOTHING")) {
                s.setInt(1, worldId);
                s.setLong(2, event.chunkKey());
                s.setLong(3, event.timestamp());
                s.setInt(4, event.sequence());
                s.setInt(5, actor);
                s.setString(6, blobId);
                s.executeUpdate();
            }
            if (!blobId.equals(blobAt(worldId, event)))
                throw new StoreException(StoreException.Reason.CORRUPT, "Event already has a different blob");
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.DISK, "Blob attachment failed", e);
        } finally {
            normalSynchronous();
        }
    }

    private static @Nullable Integer findActor(
            Connection connection, String table, long base, int world, CursorPosition event) throws SQLException {
        try (var s = connection.prepareStatement("SELECT actor FROM " + table + " WHERE w=? AND c=? AND k=?")) {
            s.setInt(1, world);
            s.setLong(2, event.chunkKey());
            s.setLong(3, ShardKeys.key(base, event.timestamp(), event.sequence()));
            try (var r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : null;
            }
        }
    }

    @Override
    public synchronized @Nullable String blobAt(int worldId, CursorPosition event) throws StoreException {
        try (var s = writer.prepareStatement("SELECT blob_id FROM blob_ref WHERE w=? AND c=? AND ts=? AND seq=?"
                + " AND NOT EXISTS(SELECT 1 FROM exclusion WHERE (exclusion.actor IS NULL OR exclusion.actor=blob_ref.actor)"
                + " AND blob_ref.ts>=from_ts AND blob_ref.ts<to_ts)")) {
            s.setInt(1, worldId);
            s.setLong(2, event.chunkKey());
            s.setLong(3, event.timestamp());
            s.setInt(4, event.sequence());
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Blob reference read failed", e);
        }
    }

    @Override
    public synchronized long collectBlobs() throws StoreException {
        requireNoScans();
        try (var s = writer.createStatement()) {
            s.executeUpdate(
                    "DELETE FROM blob_ref WHERE EXISTS(SELECT 1 FROM exclusion WHERE"
                            + " (exclusion.actor IS NULL OR exclusion.actor=blob_ref.actor) AND blob_ref.ts>=from_ts AND blob_ref.ts<to_ts)");
            return s.executeUpdate("DELETE FROM blob WHERE NOT EXISTS(SELECT 1 FROM blob_ref WHERE blob_id=blob.id)");
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Blob collection failed", e);
        }
    }

    private void fullSynchronous() throws SQLException {
        try (var s = writer.createStatement()) {
            s.execute("PRAGMA synchronous=FULL");
        }
    }

    private void normalSynchronous() {
        try (var s = writer.createStatement()) {
            s.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException ignored) {
            /* FULL is safe to retain if reset fails. */
        }
    }

    private long rewrite(@Nullable Integer actor, long from, long to, boolean remove) throws StoreException {
        Path outputFile = null;
        boolean published = false;
        try {
            List<ShardRef> inputs = maintenanceInputs(remove);
            if (!remove && inputs.size() < 2) return 0;
            try (var s = writer.createStatement()) {
                s.execute("DROP TABLE IF EXISTS temp.merge_event");
                s.execute("CREATE TEMP TABLE merge_event AS SELECT * FROM main.hot_event WHERE 0");
            }
            long copied = 0, removed = 0;
            RowDigest preserved = new RowDigest();
            Exclusion selection = new Exclusion(actor == null, actor == null ? 0 : actor, from, to);
            for (ShardRef shard : inputs) {
                try (Connection reader = readShard(shardDirectory.resolve(shard.file()))) {
                    String expected = expectedDigest(shard.id());
                    if (expected != null && !expected.equals(RowDigest.read(reader, shard.baseMillis())))
                        throw new StoreException(
                                StoreException.Reason.CORRUPT, "Input shard failed digest verification");
                    try (var s = reader.createStatement();
                            var rows = s.executeQuery("SELECT w,c,k,p,b,a,actor,cause,kind FROM ev ORDER BY w,c,k");
                            var insert = writer.prepareStatement(
                                    "INSERT INTO temp.merge_event VALUES(?,?,?,?,?,?,?,?,?,0)")) {
                        long seen = 0;
                        while (rows.next()) {
                            checkMaintenance();
                            seen++;
                            long timestamp = ShardKeys.timestampOf(shard.baseMillis(), rows.getLong(3));
                            if (remove && selection.matches(rows.getInt(7), timestamp)) {
                                removed++;
                                continue;
                            }
                            preserved.add(rows, shard.baseMillis());
                            for (int i = 1; i <= 9; i++) insert.setLong(i, rows.getLong(i));
                            insert.setLong(3, ShardKeys.key(0, timestamp, ShardKeys.sequenceOf(rows.getLong(3))));
                            insert.executeUpdate();
                            copied++;
                        }
                        if (seen != shard.rows())
                            throw new StoreException(StoreException.Reason.CORRUPT, "Input row count mismatch");
                    }
                }
            }
            long id = nextShardId();
            long base = 0, max = 0;
            String name = "shard-%012d.db".formatted(id);
            String digest = "";
            long bytes = 0;
            if (copied > 0) {
                try (var s = writer.createStatement();
                        var r = s.executeQuery("SELECT min(k),max(k) FROM temp.merge_event")) {
                    r.next();
                    base = ShardKeys.timestampOf(0, r.getLong(1));
                    max = ShardKeys.timestampOf(0, r.getLong(2));
                }
                Path file = shardDirectory.resolve(name);
                outputFile = file;
                Files.deleteIfExists(file);
                long written = writeShard(file, base, Long.MAX_VALUE, "temp.merge_event");
                if (written != copied)
                    throw new StoreException(StoreException.Reason.CORRUPT, "Rewrite count mismatch");
                try (Connection check = readShard(file)) {
                    RowDigest actual = RowDigest.readRows(check, base);
                    if (!preserved.multiset().equals(actual.multiset()))
                        throw new StoreException(StoreException.Reason.CORRUPT, "Rewrite multiset mismatch");
                    digest = actual.finish();
                }
                bytes = Files.size(file);
            }
            maintenanceProbe.accept("rewrite.output-forced");
            checkMaintenance();
            writer.setAutoCommit(false);
            if (remove) {
                try (var s = writer.prepareStatement(
                        "DELETE FROM blob_ref WHERE ts>=? AND ts<?" + (actor == null ? "" : " AND actor=?"))) {
                    s.setLong(1, from);
                    s.setLong(2, to);
                    if (actor != null) s.setInt(3, actor);
                    s.executeUpdate();
                }
                try (var s = writer.prepareStatement("INSERT INTO exclusion(actor,from_ts,to_ts) VALUES(?,?,?)")) {
                    if (actor == null) s.setNull(1, java.sql.Types.INTEGER);
                    else s.setInt(1, actor);
                    s.setLong(2, from);
                    s.setLong(3, to);
                    s.executeUpdate();
                }
                // Delete by decoded absolute timestamp; don't shift arbitrary cutoff bounds.
                try (var s = writer.prepareStatement("DELETE FROM main.hot_event WHERE (k >> 16)>=? AND (k >> 16)<?"
                        + (actor == null ? "" : " AND actor=?"))) {
                    s.setLong(1, from);
                    s.setLong(2, to);
                    if (actor != null) s.setInt(3, actor);
                    removed += s.executeUpdate();
                }
                recordGap(new GapRecord(
                        from,
                        to - 1,
                        actor == null ? GapRecord.Reason.EXPIRED : GapRecord.Reason.PURGED,
                        removed,
                        "explicit history maintenance"));
            }
            try (var s = writer.prepareStatement("UPDATE shard SET state=1 WHERE id=? AND state=0")) {
                for (ShardRef shard : inputs) {
                    s.setLong(1, shard.id());
                    s.addBatch();
                }
                s.executeBatch();
            }
            if (copied > 0) {
                try (var s = writer.prepareStatement("INSERT INTO shard VALUES(?,?,?,?,?,?,?,?,?)")) {
                    s.setLong(1, id);
                    s.setString(2, name);
                    s.setLong(3, base);
                    s.setLong(4, base);
                    s.setLong(5, max);
                    s.setLong(6, copied);
                    s.setLong(7, bytes);
                    s.setInt(8, 0);
                    s.setLong(9, System.currentTimeMillis());
                    s.executeUpdate();
                }
                saveDigest(id, digest);
            }
            audit(remove ? "REMOVE" : "COMPACT", copied > 0 ? id : 0, remove ? removed : copied);
            writer.commit();
            published = true;
            if (maintenance != null) maintenance.published = true;
            maintenanceProbe.accept("rewrite.committed");
            setAutoCommitQuietly();
            return remove ? removed : copied;
        } catch (SQLException | IOException | StoreException e) {
            clearMaintenanceHandler();
            rollbackQuietly();
            if (e instanceof StoreException failure) throw failure;
            throw new StoreException(StoreException.Reason.INTERNAL, "Shard rewrite failed", e);
        } catch (RuntimeException e) {
            clearMaintenanceHandler();
            rollbackQuietly();
            throw e;
        } finally {
            clearMaintenanceHandler();
            setAutoCommitQuietly();
            try (var s = writer.createStatement()) {
                s.execute("DROP TABLE IF EXISTS temp.merge_event");
            } catch (SQLException ignored) {
                /* Next pass recreates the temporary table. */
            }
            if (!published && outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException e) {
                    throw new StoreException(
                            StoreException.Reason.DISK, "Unpublished output cleanup failed; startup will retry", e);
                }
            }
        }
    }

    private List<ShardRef> maintenanceInputs(boolean remove) throws SQLException {
        MaintenanceControl control = maintenance;
        if (control == null) throw new IllegalStateException("Rewrite requires a budget");
        List<ShardRef> inputs = new ArrayList<>();
        long total = 0;
        try (var s = writer.prepareStatement(
                "SELECT id,file,base_ts,min_ts,max_ts,row_count FROM shard WHERE state=0 ORDER BY id LIMIT ?")) {
            s.setInt(1, control.budget.maxShards() + 1);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    control.check();
                    long count = rows.getLong(6);
                    if (count > control.budget.maxInputRows() - total || inputs.size() == control.budget.maxShards()) {
                        if (remove || inputs.size() < 2) control.defer();
                        break;
                    }
                    inputs.add(new ShardRef(
                            rows.getLong(1),
                            rows.getString(2),
                            rows.getLong(3),
                            rows.getLong(4),
                            rows.getLong(5),
                            count));
                    total += count;
                }
            }
        }
        if (remove) {
            try (var s = writer.createStatement();
                    var rows = s.executeQuery("SELECT count(*) FROM main.hot_event")) {
                rows.next();
                if (rows.getLong(1) > control.budget.maxInputRows() - total) control.defer();
            }
        }
        return inputs;
    }

    /** Exposed for tests and for {@code /trace status}: the position a scan would resume from. */
    public static CursorPosition positionOf(long chunkKey, long timestamp, int sequence) {
        return new CursorPosition(chunkKey, timestamp, sequence);
    }
}
