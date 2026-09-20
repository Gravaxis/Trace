/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.rollback;

import in.gravaxis.trace.storage.OperationProgress;
import in.gravaxis.trace.storage.OperationState;
import org.jspecify.annotations.Nullable;

/**
 * What a rollback did, in numbers.
 *
 * <p>The counts matter as much as the outcome. "Applied 412, skipped 3 that had changed since" tells
 * an administrator that three blocks were touched after the grief and were left alone; a bare
 * "done" hides that, and hiding it is how a rollback quietly destroys someone's work.
 *
 * <p>The counters are <strong>this run's</strong>, and {@link #total()} is the operation's, across
 * every run of it. Keeping them apart is not tidiness: a resumed run inherits the interrupted run's
 * totals, so an assertion about what resuming did has to be made against this run's numbers or it
 * would hold even if the resume had scanned nothing at all.
 *
 * @param refused true when the rollback did not run at all
 * @param reason why it refused, or null
 * @param operationId the durable operation this run belongs to, or 0 if it never started
 * @param state where the operation stands now
 * @param rowsScanned records read by this run
 * @param chunks chunks applied by this run
 * @param applied positions restored by this run
 * @param alreadyAtTarget positions that already held the state being restored
 * @param mismatched positions skipped because the world no longer matched the history
 * @param contendedChunks chunks a region was too busy to accept in time, so they were left undone
 * @param total the operation's counters across every run, this one included
 */
public record RollbackSummary(
        boolean refused,
        @Nullable String reason,
        long operationId,
        OperationState state,
        long rowsScanned,
        int chunks,
        int applied,
        int alreadyAtTarget,
        int mismatched,
        int contendedChunks,
        OperationProgress total) {

    public static RollbackSummary refused(String reason) {
        return new RollbackSummary(true, reason, 0, OperationState.FAILED, 0, 0, 0, 0, 0, 0, OperationProgress.NOTHING);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when the operation still has work a later run could pick up. */
    public boolean hasWorkLeft() {
        return !refused && state.isResumable();
    }

    /** A one-line form for logs, commands and the test harness. */
    public String describe() {
        if (refused) {
            return "refused: " + reason;
        }
        StringBuilder line = new StringBuilder(160);
        line.append("op=")
                .append(operationId)
                .append(" state=")
                .append(state)
                .append(" applied=")
                .append(applied)
                .append(" already=")
                .append(alreadyAtTarget)
                .append(" mismatched=")
                .append(mismatched)
                .append(" chunks=")
                .append(chunks)
                .append(" scanned=")
                .append(rowsScanned);
        if (contendedChunks > 0) {
            line.append(" contended=").append(contendedChunks);
        }
        if (total.applied() != applied || total.scanned() != rowsScanned) {
            // Only when a previous run of this operation did something, so the common case stays
            // readable and the resumed case cannot be mistaken for a single run.
            line.append(" totalApplied=")
                    .append(total.applied())
                    .append(" totalMismatched=")
                    .append(total.mismatched())
                    .append(" totalScanned=")
                    .append(total.scanned());
        }
        return line.toString();
    }

    /** Accumulates counts while a rollback runs. */
    public static final class Builder {

        private long rowsScanned;
        private int chunks;
        private int applied;
        private int alreadyAtTarget;
        private int mismatched;
        private int contendedChunks;

        public void scanned() {
            rowsScanned++;
        }

        public void chunk() {
            chunks++;
        }

        public void applied() {
            applied++;
        }

        public void alreadyThere() {
            alreadyAtTarget++;
        }

        public void mismatched() {
            mismatched++;
        }

        /** A chunk whose region would not accept the work in time. */
        public void contended() {
            contendedChunks++;
        }

        public int contendedCount() {
            return contendedChunks;
        }

        /** This run's counters, in the form the operation record stores. */
        public OperationProgress progress() {
            return new OperationProgress(applied, alreadyAtTarget, mismatched, rowsScanned, chunks);
        }

        public RollbackSummary build(long operationId, OperationState state, OperationProgress carried) {
            return new RollbackSummary(
                    false,
                    null,
                    operationId,
                    state,
                    rowsScanned,
                    chunks,
                    applied,
                    alreadyAtTarget,
                    mismatched,
                    contendedChunks,
                    carried.plus(progress()));
        }
    }
}
