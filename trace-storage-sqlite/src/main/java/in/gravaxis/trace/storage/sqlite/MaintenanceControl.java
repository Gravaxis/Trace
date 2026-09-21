/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.storage.MaintenanceBudget;
import in.gravaxis.trace.storage.MaintenanceResult;
import in.gravaxis.trace.storage.MaintenanceResult.State;
import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.sqlite.ProgressHandler;

/** One pass owns its callbacks; clear them before rollback so cancellation cannot abort cleanup. */
final class MaintenanceControl extends ProgressHandler {
    final MaintenanceBudget budget;
    private final long started = System.nanoTime();

    @Nullable
    State stopped;

    boolean published;

    MaintenanceControl(MaintenanceBudget budget) {
        this.budget = budget;
    }

    void install(Connection connection) throws SQLException {
        ProgressHandler.setHandler(connection, 1000, this);
    }

    @Override
    protected int progress() {
        if (published) return 0;
        if (stopped == null) {
            if (budget.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted())
                stopped = MaintenanceResult.State.CANCELLED;
            else if (System.nanoTime() - started >= budget.maxMillis() * 1_000_000L)
                stopped = MaintenanceResult.State.DEFERRED;
        }
        return stopped == null ? 0 : 1;
    }

    void check() throws SQLException {
        if (progress() != 0) throw new SQLException("Maintenance stopped before publication");
    }

    void defer() throws SQLException {
        stopped = MaintenanceResult.State.DEFERRED;
        check();
    }
}
