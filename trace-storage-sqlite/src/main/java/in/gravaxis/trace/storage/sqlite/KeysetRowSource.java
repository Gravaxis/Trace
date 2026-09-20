/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.core.geom.MortonRange;
import in.gravaxis.trace.storage.ScanPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One table's worth of a scan: rows from one shard, or from the hot window, in plan order.
 *
 * <p>Pages with a keyset — "give me rows beyond the last key I saw" — and never with an offset,
 * which would make the database re-count everything already returned, and never after a
 * {@code COUNT(*)} over the same predicate, which would run the expensive scan twice. Both of those
 * are named failure modes of the incumbent this project is measured against.
 *
 * <p>Each Morton range is scanned in turn. One page is one prepared-statement execution, and the
 * cursor above merges sources without any of them holding more than the current row.
 */
final class KeysetRowSource implements AutoCloseable {

    private final Connection connection;
    private final String table;
    private final long baseMillis;
    private final ScanPlan plan;
    private final List<MortonRange> ranges;
    private final long lowKey;
    private final long highKey;
    private final boolean newestFirst;
    private final int pageSize;

    private int rangeIndex;
    private @Nullable ResultSet page;
    private @Nullable PreparedStatement statement;
    private boolean exhausted;

    // Keyset cursor within the current range.
    private boolean hasLastKey;
    private long lastChunk;
    private long lastKey;

    // The row currently at the head of this source.
    private boolean hasRow;
    private long rowChunk;
    private long rowKey;
    private int rowPosition;
    private int rowBefore;
    private int rowAfter;
    private int rowActor;
    private int rowCause;
    private int rowKind;

    KeysetRowSource(Connection connection, String table, long baseMillis, ScanPlan plan) {
        this.connection = connection;
        this.table = table;
        this.baseMillis = baseMillis;
        this.plan = plan;
        this.ranges = plan.ranges();
        this.newestFirst = plan.order() == ScanPlan.Order.NEWEST_FIRST;
        this.pageSize = plan.batchSize();
        this.lowKey = ShardKeys.lowKey(baseMillis, plan.fromMillis());
        // The window is half-open in time, so the last key of the last millisecond is excluded.
        this.highKey = ShardKeys.lowKey(baseMillis, plan.toMillis()) - 1;
        this.exhausted = ranges.isEmpty() || plan.fromMillis() >= plan.toMillis();
    }

    boolean hasRow() {
        return hasRow;
    }

    long chunkKey() {
        return rowChunk;
    }

    long timestamp() {
        return ShardKeys.timestampOf(baseMillis, rowKey);
    }

    int sequence() {
        return ShardKeys.sequenceOf(rowKey);
    }

    int localPosition() {
        return rowPosition;
    }

    int beforeState() {
        return rowBefore;
    }

    int afterState() {
        return rowAfter;
    }

    int actorId() {
        return rowActor;
    }

    int cause() {
        return rowCause;
    }

    int kind() {
        return rowKind;
    }

    /** Loads the next row into this source's fields. */
    boolean advance() throws SQLException {
        hasRow = false;
        while (!exhausted) {
            if (page == null && !openNextPage()) {
                continue;
            }
            ResultSet rows = page;
            if (rows != null && rows.next()) {
                readRow(rows);
                hasLastKey = true;
                lastChunk = rowChunk;
                lastKey = rowKey;
                hasRow = true;
                return true;
            }
            closePage();
            if (!pageWasFull) {
                // A short page means this range is finished.
                nextRange();
            }
        }
        return false;
    }

    private boolean pageWasFull;
    private int rowsInPage;

    private void readRow(ResultSet rows) throws SQLException {
        rowChunk = rows.getLong(1);
        rowKey = rows.getLong(2);
        rowPosition = rows.getInt(3);
        rowBefore = rows.getInt(4);
        rowAfter = rows.getInt(5);
        rowActor = rows.getInt(6);
        rowCause = rows.getInt(7);
        rowKind = rows.getInt(8);
        rowsInPage++;
        pageWasFull = rowsInPage >= pageSize;
    }

    private boolean openNextPage() throws SQLException {
        if (rangeIndex >= ranges.size()) {
            exhausted = true;
            return false;
        }
        // Ranges arrive in ascending key order. A newest-first scan has to walk them backwards:
        // otherwise this source emits a lower chunk before a higher one, and since the merge above
        // only ever compares the current head of each source, the whole stream comes out in the
        // wrong order. A rollback folding that stream would then restore the wrong state.
        MortonRange range = ranges.get(newestFirst ? ranges.size() - 1 - rangeIndex : rangeIndex);
        PreparedStatement prepared = connection.prepareStatement(pageSql(table, newestFirst, hasLastKey));
        int index = 1;
        prepared.setInt(index++, plan.worldId());
        prepared.setLong(index++, range.low());
        prepared.setLong(index++, range.high());
        prepared.setLong(index++, lowKey);
        prepared.setLong(index++, highKey);
        if (hasLastKey) {
            prepared.setLong(index++, lastChunk);
            prepared.setLong(index++, lastKey);
        }
        prepared.setInt(index, pageSize);

        statement = prepared;
        page = prepared.executeQuery();
        rowsInPage = 0;
        pageWasFull = false;
        return true;
    }

    /**
     * The only statement shape this store uses to read events.
     *
     * <p>Package-private so a test can assert on it: no {@code OFFSET}, no {@code COUNT} over an
     * event table, and a {@code LIMIT} only ever paired with a keyset predicate. Those are the
     * properties the streaming guarantee rests on, and they are easy to lose in a later edit.
     */
    static String pageSql(String table, boolean newestFirst, boolean continuing) {
        String direction = newestFirst ? "DESC" : "ASC";
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT c, k, p, b, a, actor, cause, kind FROM ")
                .append(table)
                .append(" WHERE w = ? AND c BETWEEN ? AND ? AND k BETWEEN ? AND ?");
        if (continuing) {
            sql.append(newestFirst ? " AND (c, k) < (?, ?)" : " AND (c, k) > (?, ?)");
        }
        sql.append(" ORDER BY c ")
                .append(direction)
                .append(", k ")
                .append(direction)
                .append(" LIMIT ?");
        return sql.toString();
    }

    private void nextRange() {
        rangeIndex++;
        hasLastKey = false;
        if (rangeIndex >= ranges.size()) {
            exhausted = true;
        }
    }

    private void closePage() throws SQLException {
        if (page != null) {
            page.close();
            page = null;
        }
        if (statement != null) {
            statement.close();
            statement = null;
        }
    }

    @Override
    public void close() throws SQLException {
        closePage();
    }
}
