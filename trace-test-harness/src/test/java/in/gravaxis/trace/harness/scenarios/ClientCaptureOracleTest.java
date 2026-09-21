/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Challenges the gate itself: missing counters or changed content must not produce a pass. */
class ClientCaptureOracleTest {
    @Test
    void missingCountersAndWrongBranchesFail() {
        String empty = "captured=0 published=0 rejectedUnchanged=0 dropped=0 outOfRange=0 unconfirmed=0";
        String observed = "captured=4 published=2 rejectedUnchanged=2 dropped=0 outOfRange=0 unconfirmed=0";
        assertThatCode(() -> ClientCaptureScenario.verifyCounters(empty, observed, 4, 2, 2))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ClientCaptureScenario.verifyCounters("", "", 0, 0, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClientCaptureScenario.verifyCounters(
                        empty, observed.replace("dropped=0", "dropped=1"), 4, 2, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClientCaptureScenario.verifyCounters(empty, empty, 4, 2, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void equalCountsCannotHideWrongRowsOrDuplicateIdentities() {
        long now = System.currentTimeMillis();
        String first = "0:80:0:STONE:AIR:actor:1:1:" + now + ":0:13194139533312";
        String second = "2:80:0:AIR:DIRT:actor:2:1:" + now + ":1:13194139533312";
        String rows = first + ";" + second;
        assertThatCode(() -> ClientCaptureScenario.verifyRows(rows, 0, "actor", now))
                .doesNotThrowAnyException();
        for (String bad : new String[] {
            "",
            first,
            first + ";" + first,
            rows.replace("DIRT", "STONE"),
            rows.replace(":2:1:", ":1:1:"),
            rows.replace(":1:13194139533312", ":0:13194139533312"),
            rows.replace("13194139533312", "0")
        }) {
            assertThatThrownBy(() -> ClientCaptureScenario.verifyRows(bad, 0, "actor", now))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
