/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import java.util.function.BooleanSupplier;

/** Runs on the consumer after draining, never on a tick thread or a competing SQLite writer. */
public final class MaintenanceSchedule {
    private final EventStore store;
    private final MaintenancePolicy policy;
    private final BooleanSupplier cancelled;
    private long nextAt;
    private boolean retentionTurn;
    private volatile long completed;
    private volatile long deferred;
    private volatile long cancelledPasses;
    private volatile long noWork;
    private volatile long rows;
    private volatile boolean inProgress;
    private volatile long lastElapsedNanos;

    public MaintenanceSchedule(EventStore store, MaintenancePolicy policy, BooleanSupplier cancelled, long now) {
        this.store = store;
        this.policy = policy;
        this.cancelled = cancelled;
        nextAt = now + policy.intervalMillis();
    }

    public void tick(long now, boolean backlogged) throws StoreException {
        if (!policy.enabled() || now < nextAt) return;
        nextAt = now + policy.intervalMillis();
        if (backlogged) {
            deferred++;
            return;
        }
        var budget = new MaintenanceBudget(policy.maxInputRows(), policy.maxShards(), policy.budgetMillis(), cancelled);
        MaintenanceResult result;
        long start = System.nanoTime();
        inProgress = true;
        try {
            if (retentionTurn && policy.retentionMillis() > 0)
                result = store.expireBefore(now - policy.retentionMillis(), budget);
            else result = store.compact(budget);
        } finally {
            lastElapsedNanos = System.nanoTime() - start;
            inProgress = false;
        }
        retentionTurn = !retentionTurn;
        switch (result.state()) {
            case COMPLETED -> {
                rows += result.rows();
                completed++;
            }
            case DEFERRED -> deferred++;
            case CANCELLED -> cancelledPasses++;
            case NO_WORK -> noWork++;
        }
    }

    public String status() {
        return "enabled=" + policy.enabled() + " completed=" + completed + " deferred=" + deferred + " cancelled="
                + cancelledPasses + " noWork=" + noWork + " rows=" + rows;
    }

    public long completed() {
        return completed;
    }

    public boolean inProgress() {
        return inProgress;
    }

    public long lastElapsedNanos() {
        return lastElapsedNanos;
    }

    public long deferred() {
        return deferred;
    }
}
