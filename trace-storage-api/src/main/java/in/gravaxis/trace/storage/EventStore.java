/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import java.util.List;

/**
 * A place events are stored and read back from.
 *
 * <p>Writes arrive as batches that have already been journalled, tagged with the journal position
 * they came from, so applying the same batch twice after a crash is a no-op rather than a duplicate
 * — recovery replays whatever it cannot prove was applied. Reads go through {@link MutationCursor},
 * which streams.
 *
 * <p>Implementations are single-writer: one server per data directory (the build spec is explicit
 * that this constraint belongs on page one of the documentation, not in a footnote). Reads may run
 * from any thread.
 */
public interface EventStore extends AutoCloseable {

    /**
     * Applies a batch of journalled records.
     *
     * <p>Idempotent: a batch whose journal position is at or below {@link #appliedLsn()} is
     * discarded, which is what makes replay safe.
     *
     * @param batch the records, packed four longs each
     * @param journalPosition the journal position this batch was written at
     * @throws StoreException if the write fails
     */
    void append(RecordBatch batch, long journalPosition) throws StoreException;

    /** The highest journal position durably applied. Replay resumes from here. */
    long appliedLsn() throws StoreException;

    /**
     * Moves everything written so far out of the hot window into sealed, immutable storage.
     *
     * <p>A rollback runs against sealed data so that the rows it names keep their identity while it
     * works; sealing first is what makes an interrupted rollback resumable.
     *
     * @return the number of rows sealed
     */
    long seal() throws StoreException;

    /** Opens a streaming read. The caller closes it. */
    MutationCursor scan(ScanPlan plan) throws StoreException;

    /**
     * Records that events were lost between two timestamps.
     *
     * <p>A gap is not an error to be swallowed: a rollback whose window touches one refuses, because
     * a partly-correct world is worse than an honest refusal.
     */
    void recordGap(GapRecord gap) throws StoreException;

    /** Gaps overlapping a time window, in ascending order. */
    List<GapRecord> gapsBetween(long fromMillis, long toMillis) throws StoreException;

    /** Counters for {@code /trace status} and the health watchdog. */
    StoreStats stats() throws StoreException;

    @Override
    void close() throws StoreException;
}
