/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.metrics;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import in.gravaxis.trace.harness.HarnessResult;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Records how long ticks took, so a scenario can report percentiles instead of TPS.
 *
 * <p>TPS is clamped at 20 and hides exactly the damage worth measuring; the spec's gates are stated
 * in MSPT percentiles for that reason. On Folia this event fires per region, so the samples are a
 * mix across regions — which is the right population for "did any region stutter".
 */
public final class TickRecorder implements Listener {

    private static final int MAX_SAMPLES = 200_000;

    private final double[] samples = new double[MAX_SAMPLES];
    private int count;
    private volatile boolean recording;

    public void start(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        recording = true;
    }

    public void stop() {
        recording = false;
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (!recording) {
            return;
        }
        synchronized (this) {
            if (count < samples.length) {
                samples[count++] = event.getTickDuration();
            }
        }
    }

    /** Adds tick percentiles to the result under {@code <prefix>.*}. */
    public void report(HarnessResult result, String prefix) {
        double[] sorted;
        synchronized (this) {
            sorted = Arrays.copyOf(samples, count);
        }
        Arrays.sort(sorted);
        result.metric(prefix + ".samples", (double) sorted.length, "count");
        if (sorted.length == 0) {
            return;
        }
        result.metric(prefix + ".p50", percentile(sorted, 50.0), "ms")
                .metric(prefix + ".p99", percentile(sorted, 99.0), "ms")
                .metric(prefix + ".max", sorted[sorted.length - 1], "ms");
    }

    private static double percentile(double[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }
}
