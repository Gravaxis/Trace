/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

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
}
