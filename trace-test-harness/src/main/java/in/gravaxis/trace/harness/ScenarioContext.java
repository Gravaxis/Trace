/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.nio.file.Path;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Everything a scenario is given: the plugin to schedule through, its parameters, the result it
 * fills in, and the server's working directory.
 *
 * @param plugin the harness plugin
 * @param params parameters from {@code -Dtrace.harness.params=k=v,k=v}
 * @param result where assertions, facts and measurements go
 * @param workingDirectory the server's directory, which is also the run directory the build created
 */
public record ScenarioContext(Plugin plugin, Map<String, String> params, HarnessResult result, Path workingDirectory) {

    public Logger logger() {
        return plugin.getSLF4JLogger();
    }

    /** A parameter as an int, or {@code fallback} when it was not supplied. */
    public int intParam(String name, int fallback) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            result.failure("Parameter '" + name + "' is not an integer: " + value);
            return fallback;
        }
    }

    /** A parameter as a string, or {@code fallback} when it was not supplied. */
    public String param(String name, String fallback) {
        String value = params.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Prints the line the crash rig watches for.
     *
     * <p>The rig arms its kill timer the moment this appears, so a scenario prints it once it is
     * genuinely doing the work under test, not merely started.
     */
    public void announceReady(String what) {
        logger().info("{} ready {}", TraceHarness.MARKER, what);
    }
}
