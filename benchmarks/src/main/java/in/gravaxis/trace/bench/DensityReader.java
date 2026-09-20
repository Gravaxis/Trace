/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aggregate-only page accounting shared by private input and synthetic Trace stores. */
final class DensityReader {
    private DensityReader() {}

    static Connection openReadOnly(Path path) throws SQLException, IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Input is not a regular file");
        // URI mode=ro also prevents an absent database being silently created.
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro");
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.execute("PRAGMA trusted_schema=OFF");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    static Report measure(Path file, String label) throws SQLException, IOException {
        long started = System.nanoTime();
        long fileBytes = Files.size(file);
        try (Connection connection = openReadOnly(file)) {
            connection.setAutoCommit(false);
            long pageCount = scalar(connection, "PRAGMA page_count");
            long pageSize = scalar(connection, "PRAGMA page_size");
            long freePages = scalar(connection, "PRAGMA freelist_count");
            List<String> tables = new ArrayList<>();
            Map<String, String> owners = new HashMap<>();
            Map<String, String> types = new HashMap<>();
            // Names are metadata used only internally, never serialized. No source DDL is selected.
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT name, type, tbl_name FROM sqlite_schema WHERE type IN ('table','index') ORDER BY name")) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    owners.put(name, rows.getString(3));
                    types.put(name, type);
                    if (type.equals("table")) tables.add(name);
                }
            }
            Map<String, Long> counts = new HashMap<>();
            for (String table : tables) {
                counts.put(table, scalar(connection, "SELECT count(*) FROM \"" + table.replace("\"", "\"\"") + "\""));
            }
            List<ObjectPages> objects = new ArrayList<>();
            long objectBytes = 0;
            long blockTableBytes = 0;
            long blockIndexBytes = 0;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT name, count(*), sum(pgsize) FROM dbstat GROUP BY name ORDER BY name")) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    long bytes = rows.getLong(3);
                    String owner = owners.getOrDefault(name, "");
                    String type = types.getOrDefault(name, "schema");
                    String category = owner.equals("co_block")
                            ? "coreprotect-block"
                            : owner.equals("ev") || owner.equals("hot_event") ? "trace-event" : "other";
                    // Numeric identifiers retain table/index attribution without leaking names.
                    int tableId = tables.indexOf(owner);
                    objects.add(new ObjectPages(
                            objects.size(),
                            tableId,
                            type,
                            category,
                            counts.getOrDefault(name, -1L),
                            rows.getLong(2),
                            bytes));
                    objectBytes = Math.addExact(objectBytes, bytes);
                    if (owner.equals("co_block")) {
                        if (type.equals("table")) blockTableBytes += bytes;
                        else if (type.equals("index")) blockIndexBytes += bytes;
                    }
                }
            }
            long logicalBytes = Math.multiplyExact(pageCount, pageSize);
            long unassigned = logicalBytes - objectBytes - freePages * pageSize;
            if (unassigned < 0) throw new IOException("Page accounting exceeded logical database length");
            connection.rollback();
            if (Files.size(file) != fileBytes) throw new IOException("Input changed during measurement");
            return new Report(
                    label,
                    pageCount,
                    pageSize,
                    freePages,
                    fileBytes,
                    sidecarBytes(file, "-wal"),
                    sidecarBytes(file, "-shm"),
                    objectBytes,
                    unassigned,
                    counts.getOrDefault("co_block", 0L),
                    blockTableBytes,
                    blockIndexBytes,
                    counts.getOrDefault("ev", 0L) + counts.getOrDefault("hot_event", 0L),
                    System.nanoTime() - started,
                    List.copyOf(objects));
        }
    }

    private static long sidecarBytes(Path file, String suffix) throws IOException {
        Path sidecar = file.resolveSibling(file.getFileName() + suffix);
        return Files.exists(sidecar) ? Files.size(sidecar) : 0;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Aggregate returned no result");
            return rows.getLong(1);
        }
    }

    record ObjectPages(int objectId, int tableId, String type, String category, long rows, long pages, long bytes) {
        String json() {
            return "{\"objectId\":" + objectId + ",\"tableId\":" + tableId + ",\"type\":\"" + type
                    + "\",\"category\":\"" + category + "\",\"rows\":" + rows + ",\"pages\":" + pages
                    + ",\"bytes\":" + bytes + "}";
        }
    }

    record Report(
            String label,
            long pageCount,
            long pageSize,
            long freePages,
            long fileBytes,
            long walBytes,
            long shmBytes,
            long objectBytes,
            long unassignedBytes,
            long blockRows,
            long blockTableBytes,
            long blockIndexBytes,
            long traceRows,
            long elapsedNanos,
            List<ObjectPages> objects) {
        String json() {
            return "{\"label\":" + StorageDensity.quote(label) + ",\"measured\":true,\"pageCount\":" + pageCount
                    + ",\"pageSize\":" + pageSize + ",\"freePages\":" + freePages + ",\"fileBytes\":" + fileBytes
                    + ",\"walBytes\":" + walBytes + ",\"shmBytes\":" + shmBytes + ",\"objectBytes\":" + objectBytes
                    + ",\"unassignedBytes\":" + unassignedBytes + ",\"blockRows\":" + blockRows
                    + ",\"blockTableBytes\":" + blockTableBytes + ",\"blockIndexBytes\":" + blockIndexBytes
                    + ",\"traceRows\":" + traceRows + ",\"elapsedNanos\":" + elapsedNanos
                    + ",\"objects\":["
                    + String.join(",", objects.stream().map(ObjectPages::json).toList()) + "]}";
        }
    }
}
