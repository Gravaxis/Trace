/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/** Separate phases expose whether a scheduled pass made progress on the requested work. */
public enum MaintenanceOperation {
    COMPACT,
    VERIFY,
    RETAIN,
    CHECKPOINT,
    SEAL
}
