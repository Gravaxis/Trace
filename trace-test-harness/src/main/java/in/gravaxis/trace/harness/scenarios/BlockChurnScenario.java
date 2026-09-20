/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.harness.HarnessResult;
import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import in.gravaxis.trace.harness.metrics.HeapSampler;
import in.gravaxis.trace.harness.metrics.TickRecorder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Changes a fixed patch of blocks for a fixed number of ticks, recording tick times and heap.
 *
 * <p>This is M1's trivial workload: there is nothing of Trace's to measure yet, so what it proves is
 * that the measuring apparatus works — tick percentiles, post-GC heap, throughput, all written into
 * a result file the report task turns into the README table. When capture lands in M2 the same
 * scenario becomes a real before/after comparison, because the workload will not have changed.
 *
 * <p>All world access happens on the region that owns the target chunk, so the scenario behaves
 * identically on Paper and on Folia.
 */
public final class BlockChurnScenario implements Scenario {

    private static final int CHUNK_X = 0;
    private static final int CHUNK_Z = 0;
    private static final int EDGE = 16;

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        int ticks = context.intParam("ticks", 200);
        int blocksPerTick = Math.min(context.intParam("blocksPerTick", 256), EDGE * EDGE);
        String worldName = context.param("world", "world");

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            result.failure("No world named '" + worldName + "' on this server");
            return CompletableFuture.completedFuture(null);
        }
        int y = world.getMinHeight() + 10;
        result.detail("world", worldName)
                .detail("chunk", CHUNK_X + "," + CHUNK_Z)
                .detail("y", y)
                .detail("plan", ticks + " ticks x " + blocksPerTick + " blocks");

        CompletableFuture<Void> done = new CompletableFuture<>();
        TickRecorder tickRecorder = new TickRecorder();
        HeapSampler heapSampler = new HeapSampler();
        AtomicInteger tick = new AtomicInteger();
        long[] startedAt = new long[1];

        world.getChunkAtAsync(CHUNK_X, CHUNK_Z, true)
                .thenAccept(chunk -> {
                    heapSampler.start();
                    tickRecorder.start(context.plugin());
                    startedAt[0] = System.nanoTime();
                    context.announceReady("block-churn");

                    Bukkit.getRegionScheduler()
                            .runAtFixedRate(
                                    context.plugin(),
                                    world,
                                    CHUNK_X,
                                    CHUNK_Z,
                                    task -> {
                                        int current = tick.getAndIncrement();
                                        if (current >= ticks) {
                                            task.cancel();
                                            long elapsedNanos = System.nanoTime() - startedAt[0];
                                            tickRecorder.stop();
                                            heapSampler.stop();
                                            finish(
                                                    result,
                                                    tickRecorder,
                                                    heapSampler,
                                                    ticks,
                                                    blocksPerTick,
                                                    elapsedNanos);
                                            done.complete(null);
                                            return;
                                        }
                                        Material material = (current & 1) == 0 ? Material.STONE : Material.AIR;
                                        int baseX = CHUNK_X << 4;
                                        int baseZ = CHUNK_Z << 4;
                                        for (int i = 0; i < blocksPerTick; i++) {
                                            int x = baseX + (i & 15);
                                            int z = baseZ + ((i >> 4) & 15);
                                            // applyPhysics = false: this is a throughput workload, not a redstone test.
                                            world.getBlockAt(x, y, z).setType(material, false);
                                        }
                                    },
                                    1L,
                                    1L);
                })
                .exceptionally(t -> {
                    result.failure("Could not load chunk " + CHUNK_X + "," + CHUNK_Z + ": " + t);
                    done.complete(null);
                    return null;
                });

        return done;
    }

    private static void finish(
            HarnessResult result,
            TickRecorder tickRecorder,
            HeapSampler heapSampler,
            int ticks,
            int blocksPerTick,
            long elapsedNanos) {
        long blocks = (long) ticks * blocksPerTick;
        double seconds = elapsedNanos / 1_000_000_000.0;
        result.metric("blocks.written", (double) blocks, "count")
                .metric("blocks.perSecond", seconds > 0 ? blocks / seconds : 0, "per second")
                .metric("wall.elapsed", elapsedNanos / 1_000_000.0, "ms");
        tickRecorder.report(result, "tick");
        heapSampler.report(result, "heap");
    }
}
