/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/** Canonical absolute row encoding: independent of the shard timestamp base. */
final class RowDigest {
    private final MessageDigest digest;
    private final ByteBuffer buffer = ByteBuffer.allocate(80);
    private long count;

    RowDigest() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    void add(
            int world,
            long chunk,
            long timestamp,
            int seq,
            int position,
            int before,
            int after,
            int actor,
            int cause,
            int kind) {
        buffer.clear();
        buffer.putLong(world)
                .putLong(chunk)
                .putLong(timestamp)
                .putLong(seq)
                .putLong(position)
                .putLong(before)
                .putLong(after)
                .putLong(actor)
                .putLong(cause)
                .putLong(kind);
        digest.update(buffer.array());
        count++;
    }

    String finish() {
        buffer.clear();
        buffer.putLong(count);
        digest.update(buffer.array(), 0, Long.BYTES);
        return HexFormat.of().formatHex(digest.digest());
    }

    static String read(Connection connection, long base) throws SQLException {
        RowDigest result = new RowDigest();
        try (var s = connection.createStatement();
                var rows = s.executeQuery("SELECT w,c,k,p,b,a,actor,cause,kind FROM ev ORDER BY w,c,k")) {
            while (rows.next())
                result.add(
                        rows.getInt(1),
                        rows.getLong(2),
                        ShardKeys.timestampOf(base, rows.getLong(3)),
                        ShardKeys.sequenceOf(rows.getLong(3)),
                        rows.getInt(4),
                        rows.getInt(5),
                        rows.getInt(6),
                        rows.getInt(7),
                        rows.getInt(8),
                        rows.getInt(9));
        }
        return result.finish();
    }
}
