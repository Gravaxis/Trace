/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Reaches Trace's internal test seams without depending on its module.
 *
 * <p>The harness is only allowed to depend on the published API, and the published API deliberately
 * does not have a rollback entry point yet — designing one to make a test pass would be the tail
 * wagging the dog. So the two internal hooks are called reflectively, and this class is where that
 * ugliness is contained. When the command grammar and the public API land, this goes away.
 */
public final class TracePluginBridge {

    private final Plugin plugin;
    private final Method rollback;
    private final Method counters;

    private TracePluginBridge(Plugin plugin, Method rollback, Method counters) {
        this.plugin = plugin;
        this.rollback = rollback;
        this.counters = counters;
    }

    /** The bridge, or null when Trace is not installed or does not expose the seams. */
    public static @Nullable TracePluginBridge find() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Trace");
        if (plugin == null) {
            return null;
        }
        try {
            Method rollback = plugin.getClass()
                    .getMethod("runRollback", String.class, int.class, int.class, int.class, int.class, long.class);
            Method counters = plugin.getClass().getMethod("captureCounters");
            return new TracePluginBridge(plugin, rollback, counters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Runs a rollback. Blocking: call it from the async scheduler. */
    public String rollback(String world, int x, int y, int z, int radius, long sinceMillis) throws Exception {
        return String.valueOf(rollback.invoke(plugin, world, x, y, z, radius, sinceMillis));
    }

    public String captureCounters() throws Exception {
        return String.valueOf(counters.invoke(plugin));
    }

    /** Reads {@code name=<number>} out of one of the one-line summaries. */
    public static long counter(String summary, String name) {
        for (String part : summary.split("[ ,]")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).equals(name)) {
                try {
                    return Long.parseLong(part.substring(equals + 1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
