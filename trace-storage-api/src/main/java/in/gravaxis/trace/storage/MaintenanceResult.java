/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/** An aborted pass must be distinguishable from a pass that published changes. */
public record MaintenanceResult(State state, long rows) {
    public enum State {
        COMPLETED,
        PROGRESSED,
        QUARANTINED,
        UNVERIFIED,
        NO_WORK,
        DEFERRED,
        CANCELLED
    }
}
