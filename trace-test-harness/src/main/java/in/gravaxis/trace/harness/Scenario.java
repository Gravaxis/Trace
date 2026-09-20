/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import org.bukkit.plugin.Plugin;

/**
 * One thing the harness can do to a running server.
 *
 * <p>A scenario runs on the global region scheduler after the server has finished loading, records
 * what it observed on the {@link HarnessResult}, and returns. The harness writes the result and
 * shuts the server down; the build reads it.
 */
@FunctionalInterface
public interface Scenario {

    /**
     * Runs the scenario.
     *
     * @param plugin the harness plugin, for scheduling and logging
     * @param result collects assertions and facts
     * @throws Exception anything thrown is recorded as a failure, never swallowed
     */
    void run(Plugin plugin, HarnessResult result) throws Exception;
}
