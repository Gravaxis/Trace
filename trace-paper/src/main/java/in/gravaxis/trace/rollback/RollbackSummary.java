/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.rollback;

import org.jspecify.annotations.Nullable;

/**
 * What a rollback did, in the terms an operator needs.
 *
 * <p>The counts matter as much as the outcome. "Applied 412, skipped 3 that had changed since" tells
 * an administrator that three blocks were touched after the grief and were left alone; a bare
 * "done" hides that, and hiding it is how a rollback quietly destroys someone's work.
 *
 * @param refused true when the rollback did not run at all
 * @param reason why it refused, or null
 * @param rowsScanned records read from storage
 * @param chunks chunks touched
 * @param applied positions restored
 * @param alreadyAtTarget positions already holding the state to restore
 * @param mismatched positions skipped because the world no longer matched the history
 */
public record RollbackSummary(
        boolean refused,
        @Nullable String reason,
        long rowsScanned,
        int chunks,
        int applied,
        int alreadyAtTarget,
        int mismatched) {

    public static RollbackSummary refused(String reason) {
        return new RollbackSummary(true, reason, 0, 0, 0, 0, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A one-line form for logs, commands and the test harness. */
    public String describe() {
        if (refused) {
            return "refused: " + reason;
        }
        return "applied=%d already=%d mismatched=%d chunks=%d scanned=%d"
                .formatted(applied, alreadyAtTarget, mismatched, chunks, rowsScanned);
    }

    /** Accumulates counts while a rollback runs. */
    public static final class Builder {

        private long rowsScanned;
        private int chunks;
        private int applied;
        private int alreadyAtTarget;
        private int mismatched;

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

        public RollbackSummary build() {
            return new RollbackSummary(false, null, rowsScanned, chunks, applied, alreadyAtTarget, mismatched);
        }
    }
}
