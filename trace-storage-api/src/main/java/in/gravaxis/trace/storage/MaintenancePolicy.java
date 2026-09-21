/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

/** Typed operator choices; retention age zero disables destructive scheduling. */
public record MaintenancePolicy(
        boolean enabled,
        long intervalMillis,
        long maxInputRows,
        int maxShards,
        long budgetMillis,
        long retentionMillis) {
    public MaintenancePolicy {
        if (intervalMillis < 1 || retentionMillis < 0)
            throw new IllegalArgumentException("Invalid maintenance interval/retention");
        new MaintenanceBudget(maxInputRows, maxShards, budgetMillis, () -> false);
    }

    public static MaintenancePolicy defaults() {
        return new MaintenancePolicy(true, 60_000, 100_000, 8, 50, 0);
    }
}
