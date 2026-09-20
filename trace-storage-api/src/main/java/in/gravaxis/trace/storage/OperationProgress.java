/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * What a rollback has done so far, counted.
 *
 * <p>These are cumulative across every run of one operation: a resume adds to them rather than
 * starting again. Anything that wants to know what a single run did takes a difference — and a test
 * asserting on a resumed run must do exactly that, or it is asserting on the interrupted run's
 * numbers and would pass having resumed nothing.
 *
 * @param applied positions restored
 * @param already positions that already held the state being restored
 * @param mismatched positions left alone because someone changed them after the history being undone
 * @param scanned rows read
 * @param chunks chunks visited
 */
public record OperationProgress(long applied, long already, long mismatched, long scanned, long chunks) {

    public static final OperationProgress NOTHING = new OperationProgress(0, 0, 0, 0, 0);

    /** This progress plus another run's. */
    public OperationProgress plus(OperationProgress other) {
        return new OperationProgress(
                applied + other.applied,
                already + other.already,
                mismatched + other.mismatched,
                scanned + other.scanned,
                chunks + other.chunks);
    }

    /** What changed between an earlier snapshot and this one. */
    public OperationProgress minus(OperationProgress earlier) {
        return new OperationProgress(
                applied - earlier.applied,
                already - earlier.already,
                mismatched - earlier.mismatched,
                scanned - earlier.scanned,
                chunks - earlier.chunks);
    }
}
