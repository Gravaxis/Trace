/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

/**
 * An inclusive range of Morton chunk keys to scan.
 *
 * @param low lowest key in the range, inclusive
 * @param high highest key in the range, inclusive
 * @param coarse true when the range covers chunks outside the query as well, so every row it
 *     returns still has to pass the post-filter. Exact ranges are post-filtered too — correctness
 *     never depends on the cover being tight — but the flag tells the planner how much slack it
 *     accepted, and {@code /trace status} how often the cap is being hit.
 */
public record MortonRange(long low, long high, boolean coarse) {

    public MortonRange {
        if (low > high) {
            throw new IllegalArgumentException("Inverted Morton range: " + low + " > " + high);
        }
    }

    public boolean contains(long key) {
        return key >= low && key <= high;
    }

    public long size() {
        return high - low + 1;
    }
}
