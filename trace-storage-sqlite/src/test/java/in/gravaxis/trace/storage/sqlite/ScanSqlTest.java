/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.ScanPlan;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the two SQL habits the streaming guarantee cannot survive.
 *
 * <p>{@code OFFSET} pagination makes the database re-walk everything it has already returned, and a
 * {@code COUNT(*)} over the same predicate as the data query runs the expensive scan twice. Both are
 * named defects of the incumbent Trace is measured against, and both are one careless edit away at
 * any time — so the statement shape is asserted rather than trusted.
 */
class ScanSqlTest {

    @Test
    @DisplayName("every page statement is a keyset scan with a limit, never an offset")
    void pageStatementsAreKeysetScans() {
        for (String table : new String[] {"ev", "hot_event"}) {
            for (boolean newestFirst : new boolean[] {true, false}) {
                for (boolean continuing : new boolean[] {true, false}) {
                    String sql = KeysetRowSource.pageSql(table, newestFirst, continuing);
                    assertThat(sql).doesNotContainIgnoringCase("offset");
                    assertThat(sql).doesNotContainIgnoringCase("count(");
                    assertThat(sql).containsIgnoringCase("limit ?");
                    assertThat(sql).contains("ORDER BY c " + (newestFirst ? "DESC" : "ASC"));
                    if (continuing) {
                        assertThat(sql)
                                .as("a limit without a keyset predicate would skip or repeat rows")
                                .contains(newestFirst ? "(c, k) < (?, ?)" : "(c, k) > (?, ?)");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("a resumed scan seeks from its first page, rather than reading and discarding")
    void aResumedScanSeeksImmediately() throws SQLException {
        ScanPlan plan = ScanPlan.of(
                1, BlockBox.around(0, 64, 0, 32), 1_800_000_000_000L, 1_800_000_060_000L, ScanPlan.Order.NEWEST_FIRST);

        // No assertion about the rows a resumed scan returns can tell a seek from a scan that reads
        // everything and throws the first half away: both return the same rows. This is the
        // difference, and it is the whole reason resuming an interrupted rollback costs the
        // remainder rather than the job.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (KeysetRowSource fresh = new KeysetRowSource(connection, "ev", 0L, plan)) {
                assertThat(fresh.isSeeking())
                        .as("a scan from the beginning has nothing to seek past")
                        .isFalse();
            }
            ScanPlan resumed = plan.resumeAfter(new CursorPosition(9, 1_800_000_030_000L, 4));
            try (KeysetRowSource source = new KeysetRowSource(connection, "ev", 0L, resumed)) {
                assertThat(source.isSeeking())
                        .as("a resumed scan must carry the keyset predicate into its very first page")
                        .isTrue();
            }
        }
    }
}
