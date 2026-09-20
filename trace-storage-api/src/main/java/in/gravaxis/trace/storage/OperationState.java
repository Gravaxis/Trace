/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/**
 * Where a rollback got to.
 *
 * <p>Ids are explicit and persisted, for the reason {@link GapRecord.Reason} gives: declaration
 * order is not a stable identifier once rows are on disk.
 *
 * <p>There is deliberately no INTERRUPTED state. A process killed by {@code kill -9} cannot write
 * one, so the honest encoding of "interrupted" is an operation still marked RUNNING whose run id
 * belongs to a process that is gone.
 */
public enum OperationState {

    /** Started and not finished. After a restart, this means interrupted. */
    RUNNING(1),

    /** The scan reached the end and every position was dealt with. */
    DONE(2),

    /**
     * The scan finished, but at least one chunk could not be applied in time.
     *
     * <p>Reported as its own state rather than folded into DONE, because "finished" and "finished
     * except for the parts that were busy" are different answers to the only question an operator
     * is asking.
     */
    PARTIAL(3),

    /** Stopped by an error. The cursor says how far it is safe to assume the world was changed. */
    FAILED(4),

    /** Stopped because someone asked it to stop. */
    CANCELLED(5);

    private final int id;

    OperationState(int id) {
        this.id = id;
    }

    /** The persisted id. Stable forever. */
    public int id() {
        return id;
    }

    /** True when there may be work left that a later run could pick up. */
    public boolean isResumable() {
        return this == RUNNING || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    /** The state for a persisted id. */
    public static OperationState byId(int id) {
        for (OperationState state : values()) {
            if (state.id == id) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown operation state id: " + id);
    }
}
