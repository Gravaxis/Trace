/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.util.concurrent.CompletableFuture;

/**
 * One thing the harness can do to a running server.
 *
 * <p>A scenario starts on the global region scheduler once the server has loaded, and completes the
 * returned future when it is finished. Work that spans ticks schedules itself through the region,
 * entity or async schedulers and completes the future at the end: nothing here may block a tick
 * thread, on Paper or on Folia.
 *
 * <p>When the future completes, the harness writes the result and shuts the server down. A scenario
 * that never completes is killed by the build's timeout and reported as a failure.
 */
@FunctionalInterface
public interface Scenario {

    /**
     * Starts the scenario.
     *
     * @param context the plugin, the parameters, and the result to fill in
     * @return a future completed when the scenario is done; an exceptional completion is recorded
     *     as a failure
     */
    CompletableFuture<Void> run(ScenarioContext context);
}
