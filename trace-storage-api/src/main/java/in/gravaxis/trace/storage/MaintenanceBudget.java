/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import java.util.function.BooleanSupplier;

/** Admission limits plus cooperative cancellation; no promise about stalled OS calls. */
public record MaintenanceBudget(long maxInputRows, int maxShards, long maxMillis, BooleanSupplier cancelled) {
    public MaintenanceBudget {
        if (maxInputRows < 1 || maxShards < 2 || maxShards > 1024 || maxMillis < 1 || maxMillis > 60_000)
            throw new IllegalArgumentException("Invalid maintenance budget");
    }

    public static MaintenanceBudget defaults() {
        return new MaintenanceBudget(1_000_000, 16, 5_000, () -> false);
    }
}
