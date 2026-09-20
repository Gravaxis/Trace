/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A place events are stored and read back from.
 *
 * <p>Writes arrive as batches that have already been journalled, tagged with the journal position
 * they came from, so applying the same batch twice after a crash is a no-op rather than a duplicate
 * — recovery replays whatever it cannot prove was applied. Reads go through {@link MutationCursor},
 * which streams.
 *
 * <p>Implementations are single-writer: one server per data directory (the build spec is explicit
 * that this constraint belongs on page one of the documentation, not in a footnote).
 *
 * <p><strong>Every method here may be called from any thread.</strong> That is not a courtesy: the
 * pipeline appends from its consumer thread while a rollback scans from the async scheduler and a
 * command asks for {@link #stats()}, so an implementation that is merely single-writer-by-convention
 * will corrupt a transaction sooner or later. Implementations serialise internally; a
 * {@link MutationCursor} handed back to a caller is not shared and is used by one thread at a
 * time.
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

    /**
     * The highest journal position durably applied, or -1 when nothing has been.
     *
     * <p>Replay resumes from the next position. It cannot be zero-for-nothing: the journal's first
     * frame is written at position zero.
     */
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

    /**
     * Records that a rollback has started, and returns its id.
     *
     * <p>Written before the first block is touched. An operation that exists with no matching change
     * in the world is harmless; a world changed by an operation that was never recorded cannot be
     * continued or explained.
     */
    long beginOperation(RollbackOperation operation) throws StoreException;

    /**
     * Durably records how far an operation has got.
     *
     * <p>The cursor is a promise: everything up to and including it has been dealt with, so a later
     * run may start after it. A caller that cannot make that promise for some part of the work —
     * a chunk it could not apply — must stop advancing the cursor rather than carry on past it.
     *
     * @param cursor the last position durably dealt with, or null to leave the stored one alone
     */
    void checkpointOperation(long operationId, @Nullable CursorPosition cursor, OperationProgress progress)
            throws StoreException;

    /** Records an operation's final state and counters. */
    void finishOperation(long operationId, OperationState state, OperationProgress progress) throws StoreException;

    /** One operation, or null if there is no such id. */
    @Nullable
    RollbackOperation operation(long operationId) throws StoreException;

    /**
     * Operations that may still have work left, oldest first.
     *
     * <p>What a restart reads to find out whether the last run left a world half-restored.
     */
    List<RollbackOperation> unfinishedOperations() throws StoreException;

    @Override
    void close() throws StoreException;
}
