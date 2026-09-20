/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Map<String, Method> optional;

    private TracePluginBridge(Plugin plugin, Method rollback, Method counters, Map<String, Method> optional) {
        this.plugin = plugin;
        this.rollback = rollback;
        this.counters = counters;
        this.optional = optional;
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
            Map<String, Method> optional = new LinkedHashMap<>();
            // Seams a given Trace build may not have. A scenario that needs one says so by name and
            // fails with that sentence, rather than with a reflection stack trace.
            put(optional, plugin, "resumeRollback", long.class);
            put(optional, plugin, "cancelRollback", long.class);
            put(optional, plugin, "unfinishedRollbacks");
            put(optional, plugin, "gapsBetween", long.class, long.class);
            put(
                    optional,
                    plugin,
                    "storedPositions",
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    long.class,
                    long.class);
            return new TracePluginBridge(plugin, rollback, counters, optional);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static void put(Map<String, Method> into, Plugin plugin, String name, Class<?>... types) {
        try {
            into.put(name, plugin.getClass().getMethod(name, types));
        } catch (NoSuchMethodException e) {
            // Left out on purpose: absent means absent, and callers check.
        }
    }

    /** True when this Trace build has the named seam. */
    public boolean has(String seam) {
        return optional.containsKey(seam);
    }

    /**
     * Calls an optional seam.
     *
     * @throws IllegalStateException when this Trace build does not have it, naming the seam
     */
    public String call(String seam, Object... arguments) throws Exception {
        Method method = optional.get(seam);
        if (method == null) {
            throw new IllegalStateException("This Trace build has no " + seam + " seam");
        }
        return String.valueOf(method.invoke(plugin, arguments));
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
