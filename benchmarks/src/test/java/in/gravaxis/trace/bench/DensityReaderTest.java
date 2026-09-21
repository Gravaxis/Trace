/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DensityReaderTest {
    @TempDir
    Path directory;

    @Test
    void attributesPagesWithoutDisclosingValuesOrArbitraryIdentifiers() throws Exception {
        Path file = directory.resolve("fixture.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var s = c.createStatement()) {
            s.execute("CREATE TABLE co_block(actor TEXT, payload BLOB)");
            s.execute("CREATE INDEX block_actor ON co_block(actor)");
            s.execute("INSERT INTO co_block VALUES ('PRIVATE_PLAYER_192.0.2.17_UUID', zeroblob(12000))");
            s.execute("CREATE TABLE \"PRIVATE_TABLE_CANARY\"(value TEXT)");
            s.execute("INSERT INTO PRIVATE_TABLE_CANARY VALUES ('PRIVATE_COORDINATE_CANARY')");
            s.execute("CREATE TABLE discarded(payload BLOB)");
            s.execute("INSERT INTO discarded VALUES (zeroblob(50000))");
            s.execute("DROP TABLE discarded");
        }
        long before = Files.size(file);
        var report = DensityReader.measure(file, "fixture");
        assertThat(report.freePages()).isPositive();
        assertThat(report.blockRows()).isEqualTo(1);
        assertThat(report.blockIndexBytes()).isPositive();
        assertThat(report.blockTableBytes()).isPositive();
        assertThat(report.objectBytes() + report.freePages() * report.pageSize() + report.unassignedBytes())
                .isEqualTo(report.pageCount() * report.pageSize());
        assertThat(report.json()).doesNotContain("PRIVATE", "192.0.2.17", "actor", "payload", file.toString());
        assertThat(Files.size(file)).isEqualTo(before);
        try (var c = DensityReader.openReadOnly(file);
                var s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("DELETE FROM co_block")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void missingInputIsNotCreated() {
        Path absent = directory.resolve("missing.db");
        assertThatThrownBy(() -> DensityReader.measure(absent, "fixture")).isInstanceOf(Exception.class);
        assertThat(absent).doesNotExist();
    }

    @Test
    void traceUsesRealAppendAndSealAndCountsEveryGeneratedEvent() throws Exception {
        for (boolean incremental : new boolean[] {false, true}) {
            Path scratch = directory.resolve("trace-" + incremental);
            var reports = StorageDensity.measureTrace(scratch, 200, 1000, 50, incremental);
            assertThat(reports.stream()
                            .mapToLong(DensityReader.Report::traceRows)
                            .sum())
                    .isEqualTo(200);
            assertThat(reports.stream().filter(r -> r.traceRows() > 0).count()).isGreaterThan(1);
            assertThat(reports).allSatisfy(r -> assertThat(r.unassignedBytes()).isZero());
        }
    }
}
