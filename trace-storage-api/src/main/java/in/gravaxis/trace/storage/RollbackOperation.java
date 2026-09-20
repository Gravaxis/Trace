/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.geom.BlockBox;
import org.jspecify.annotations.Nullable;

/**
 * A rollback, as a durable record of what it is doing and how far it has got.
 *
 * <p>Without this, a rollback interrupted halfway leaves a world that is neither the old one nor the
 * new one, and nothing anywhere says so. With it, the next start can see the operation, say what was
 * left undone, and continue from the exact row the scan had reached.
 *
 * <p>The time window is stored because it must not be recalculated on resume. A rollback records
 * its own writes, so a resumed run computing a fresh "now" would take in the rows the first run
 * wrote and start undoing its own work.
 *
 * @param id assigned by the store
 * @param runId the process instance that created it; a resume refuses an operation whose run id is
 *     the current process, because that one may still be running
 * @param worldId the world, in Trace's own numbering
 * @param box the region being rolled back
 * @param fromMillis window start, inclusive
 * @param toMillis window end, exclusive
 * @param actorId who asked for it
 * @param state where it got to
 * @param cursor the last position durably dealt with, or null if it never checkpointed
 * @param progress counters accumulated across every run of this operation
 * @param startedAtMillis when it began
 * @param updatedAtMillis when it last checkpointed or finished
 */
public record RollbackOperation(
        long id,
        String runId,
        int worldId,
        BlockBox box,
        long fromMillis,
        long toMillis,
        int actorId,
        OperationState state,
        @Nullable CursorPosition cursor,
        OperationProgress progress,
        long startedAtMillis,
        long updatedAtMillis) {

    /** An operation about to be started, with no id and nothing done yet. */
    public static RollbackOperation starting(
            String runId, int worldId, BlockBox box, long fromMillis, long toMillis, int actorId, long nowMillis) {
        return new RollbackOperation(
                0,
                runId,
                worldId,
                box,
                fromMillis,
                toMillis,
                actorId,
                OperationState.RUNNING,
                null,
                OperationProgress.NOTHING,
                nowMillis,
                nowMillis);
    }

    /** The same operation, with the id the store assigned it. */
    public RollbackOperation withId(long assignedId) {
        return new RollbackOperation(
                assignedId,
                runId,
                worldId,
                box,
                fromMillis,
                toMillis,
                actorId,
                state,
                cursor,
                progress,
                startedAtMillis,
                updatedAtMillis);
    }

    /** True when this operation has work left and nothing is known to be running it. */
    public boolean isResumable(String currentRunId) {
        return state.isResumable() && !runId.equals(currentRunId);
    }
}
