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
    private int phase;
    private volatile long progressed;
    private volatile long quarantined;
    private volatile long unverified;
    private volatile long completed;
    private volatile long compactions;
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
        MaintenanceOperation operation = MaintenanceOperation.values()[phase];
        long start = System.nanoTime();
        inProgress = true;
        try {
            if (operation == MaintenanceOperation.RETAIN && policy.retentionMillis() == 0)
                result = new MaintenanceResult(MaintenanceResult.State.NO_WORK, 0);
            else result = store.maintain(operation, budget, now - policy.retentionMillis());
        } catch (StoreException e) {
            // A bad compaction input must not starve the following verification phase.
            phase = (phase + 1) % MaintenanceOperation.values().length;
            throw e;
        } finally {
            lastElapsedNanos = System.nanoTime() - start;
            inProgress = false;
        }
        if (result.state() == MaintenanceResult.State.PROGRESSED) nextAt = now;
        else if (result.state() != MaintenanceResult.State.DEFERRED)
            phase = (phase + 1) % MaintenanceOperation.values().length;
        if (phase != 0 && result.state() != MaintenanceResult.State.DEFERRED) nextAt = now;
        switch (result.state()) {
            case COMPLETED -> {
                rows += result.rows();
                completed++;
                if (operation == MaintenanceOperation.COMPACT) compactions++;
            }
            case PROGRESSED -> progressed++;
            case QUARANTINED -> quarantined++;
            case UNVERIFIED -> unverified++;
            case DEFERRED -> deferred++;
            case CANCELLED -> cancelledPasses++;
            case NO_WORK -> noWork++;
        }
    }

    public String status() {
        return "enabled=" + policy.enabled() + " completed=" + completed + " deferred=" + deferred + " cancelled="
                + cancelledPasses + " noWork=" + noWork + " rows=" + rows + " progressed=" + progressed
                + " quarantined=" + quarantined + " unverified=" + unverified + " compactions=" + compactions;
    }

    public long completed() {
        return completed;
    }

    public long compactions() {
        return compactions;
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
