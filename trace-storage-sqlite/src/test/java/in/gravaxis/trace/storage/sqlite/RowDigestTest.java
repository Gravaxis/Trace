/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RowDigestTest {
    private static void row(RowDigest digest, int value) {
        digest.add(1, 2, 3, 4, 5, value, 7, 8, 9, 10);
    }

    @Test
    void multisetIgnoresOrderButRetainsPairedDuplicatesAndPayloadChanges() {
        var forward = new RowDigest();
        row(forward, 11);
        row(forward, 12);
        var reverse = new RowDigest();
        row(reverse, 12);
        row(reverse, 11);
        assertThat(forward.multiset()).isEqualTo(reverse.multiset());
        assertThat(forward.finish()).isNotEqualTo(reverse.finish());
        var empty = new RowDigest();
        var twice = new RowDigest();
        row(twice, 11);
        row(twice, 11);
        assertThat(twice.multiset()).isNotEqualTo(empty.multiset());
        // Exclude the count prefix too: this specifically detects XOR cancellation.
        assertThat(twice.multiset().substring(16)).isNotEqualTo(empty.multiset().substring(16));
        assertThat(twice.multiset()).isNotEqualTo(forward.multiset());
    }
}
