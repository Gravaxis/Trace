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
import in.gravaxis.trace.harness.TracePluginBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A rollback that is stopped halfway and then continued.
 *
 * <p>The M2 definition of done asks that an interrupted rollback resumes. Interrupting one is the
 * hard part of testing it: a rollback that is allowed to finish and then "resumed" proves nothing,
 * and a crash timed to land inside one is a coin toss. So this cancels a running rollback, which
 * stops it at the next chunk boundary with a durable cursor and work left over — the same state a
 * crash leaves, reached deliberately.
 *
 * <p>What is asserted is the <em>difference</em> the second run made, not the totals. A resumed run
 * inherits the interrupted run's counters, so an assertion on the totals would hold even if the
 * resume had scanned nothing at all. The world is checked too, because a counter is a claim and the
 * blocks are the fact.
 */
public final class RollbackResumeScenario implements Scenario {

    /** Enough chunks that a cancellation has boundaries to stop at. */
    private static final int[][] AREA_CHUNKS = {{0, 0}, {0, 8}, {8, 0}, {8, 8}, {16, 0}, {16, 8}};

    private static final int BLOCKS_PER_AREA = 40;
    private static final int CENTRE = 128;
    private static final int RADIUS = 400;

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        String worldName = context.param("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            result.failure("No world named '" + worldName + "'");
            return CompletableFuture.completedFuture(null);
        }
        TracePluginBridge trace = TracePluginBridge.find();
        if (trace == null) {
            result.failure("Trace is not installed, so there is nothing to test");
            return CompletableFuture.completedFuture(null);
        }
        for (String seam : new String[] {"cancelRollback", "resumeRollback", "unfinishedRollbacks"}) {
            if (!trace.has(seam)) {
                result.failure("This Trace build has no " + seam + " seam");
                return CompletableFuture.completedFuture(null);
            }
        }

        int y = world.getMinHeight() + 10;
        List<Area> areas = new ArrayList<>();
        for (int[] chunk : AREA_CHUNKS) {
            areas.add(Area.at(chunk[0], chunk[1], y));
        }
        result.detail("areas", areas.size()).detail("blocks", areas.size() * BLOCKS_PER_AREA);

        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<?>[] loaded = areas.stream()
                .map(area -> world.getChunkAtAsync(area.chunkX(), area.chunkZ(), true))
                .toArray(CompletableFuture<?>[]::new);

        String phase = System.getProperty("trace.harness.scenario", "rollback-resume");
        if (phase.equals("rollback-crash-write") || phase.equals("rollback-crash-verify")) {
            CompletableFuture.allOf(loaded)
                    .thenRun(() -> Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
                        try {
                            if (phase.equals("rollback-crash-write")) {
                                result.require(
                                        trace.call("armRollbackCrash").equals("armed"), "Crash hook was not armed");
                                String outcome = trace.rollback(world.getName(), CENTRE, y, CENTRE, RADIUS, 3_600_000);
                                result.failure("Rollback returned instead of reaching the crash hook: " + outcome);
                                done.complete(null);
                            } else {
                                String unfinished = trace.call("unfinishedRollbacks");
                                result.detail("crash.unfinished", unfinished);
                                result.require(
                                        unfinished.contains("id=1") && unfinished.contains("RUNNING"),
                                        "No killed running operation: " + unfinished);
                                long checkpointed = TracePluginBridge.counter(unfinished, "applied");
                                result.require(
                                        checkpointed > 0 && checkpointed < areas.size() * BLOCKS_PER_AREA,
                                        "Kill did not interrupt partial work: " + unfinished);
                                String compacted = trace.call("compactStorage");
                                result.detail("crash.compaction", compacted);
                                result.require(
                                        TracePluginBridge.counter(compacted, "rewritten") > 0,
                                        "Compaction branch was not exercised");
                                String resumed = trace.call("resumeRollback", 1L);
                                result.detail("crash.resumed", resumed);
                                result.require(
                                        resumed.contains("state=DONE"), "Crash resume did not finish: " + resumed);
                                result.require(
                                        TracePluginBridge.counter(resumed, "applied") > 0, "Resume applied nothing");
                                result.detail("crash.checkpointReached", true);
                                verifyWorld(context, world, areas, done);
                            }
                        } catch (Exception e) {
                            result.failure("Crash resume scenario failed: " + e);
                            done.complete(null);
                        }
                    }))
                    .exceptionally(t -> {
                        result.failure("Chunk load failed: " + t);
                        done.complete(null);
                        return null;
                    });
            return done;
        }

        CompletableFuture.allOf(loaded)
                .thenCompose(ignored -> onEachRegion(context, world, areas, area -> {
                    result.require(
                            world.addPluginChunkTicket(area.chunkX(), area.chunkZ(), context.plugin()),
                            "Fixture must retain its otherwise playerless chunk through confirmation");
                    result.require(
                            Bukkit.isOwnedByCurrentRegion(world, area.chunkX(), area.chunkZ()), "Fixture owns chunk");
                    for (int[] position : area.positions()) {
                        world.getBlockAt(position[0], position[1], position[2]).setType(Material.STONE, false);
                    }
                }))
                .thenCompose(ignored -> onEachRegion(context, world, areas, area -> {
                    for (int[] position : area.positions()) {
                        Block block = world.getBlockAt(position[0], position[1], position[2]);
                        HarnessPlayers.fireBlockBreak(block);
                        block.setType(Material.AIR, false);
                    }
                }))
                // Capture commits at tick end, once per region. Without waiting for that, a
                // regionised server can reach the rollback with most of the history still staged:
                // an earlier version of this scenario rolled back 8 events out of 240 and blamed
                // the rollback.
                .thenCompose(ignored -> afterTicks(context, world, areas, 5L))
                .thenRun(() -> {
                    String counters;
                    try {
                        counters = trace.captureCounters();
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                    result.detail("capture.confirmed", counters);
                    result.require(
                            TracePluginBridge.counter(counters, "unreadable") == 0,
                            "Confirmation could not read retained fixture chunks: " + counters);
                    result.require(
                            TracePluginBridge.counter(counters, "published") == areas.size() * BLOCKS_PER_AREA,
                            "Fixture did not publish every changed position: " + counters);
                    if (phase.equals("rollback-crash-prepare")) {
                        result.detail("crash.prepared", true);
                        done.complete(null); // Normal server shutdown saves the broken world and history.
                    } else if (phase.startsWith("storage-")) {
                        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
                            try {
                                if (phase.equals("storage-scheduled")) {
                                    var ticks = new in.gravaxis.trace.harness.metrics.TickRecorder();
                                    ticks.start(context.plugin());
                                    trace.call("sealForScheduledTest");
                                    // A second real captured batch creates another input shard.
                                    onEachRegion(context, world, areas, area -> {
                                                for (int[] position : area.positions()) {
                                                    Block block =
                                                            world.getBlockAt(position[0], position[1], position[2]);
                                                    block.setType(Material.STONE, false);
                                                    HarnessPlayers.fireBlockBreak(block);
                                                    block.setType(Material.AIR, false);
                                                }
                                            })
                                            .thenCompose(ignored -> afterTicks(context, world, areas, 5L))
                                            .thenRun(() -> Bukkit.getAsyncScheduler()
                                                    .runNow(context.plugin(), next -> {
                                                        try {
                                                            String scheduled =
                                                                    trace.call("scheduledMaintenanceForTest");
                                                            result.detail("maintenance.scheduled", scheduled);
                                                            result.require(
                                                                    TracePluginBridge.counter(scheduled, "completed")
                                                                            > 0,
                                                                    "No scheduled pass completed");
                                                            result.require(
                                                                    TracePluginBridge.counter(scheduled, "rows") > 0,
                                                                    "Scheduled pass rewrote no rows");
                                                            result.require(
                                                                    TracePluginBridge.counter(scheduled, "shards") == 1,
                                                                    "Inputs were not merged");
                                                        } catch (Exception e) {
                                                            result.failure("Scheduled maintenance failed: " + e);
                                                        } finally {
                                                            ticks.stop();
                                                            ticks.report(result, "maintenance.window.tick");
                                                            result.require(
                                                                    ticks.sampleCount() > 0,
                                                                    "No maintenance-window tick samples");
                                                            result.detail(
                                                                    "maintenance.tickScope",
                                                                    "Whole synthetic capture/seal/scheduling window; mixed regions on Folia; no control or causal latency claim");
                                                        }
                                                        done.complete(null);
                                                    }))
                                            .exceptionally(error -> {
                                                ticks.stop();
                                                result.failure("Second batch failed: " + error);
                                                done.complete(null);
                                                return null;
                                            });
                                    return;
                                }
                                String action = phase.substring("storage-".length());
                                String changed = trace.call("maintenanceForTest", action);
                                result.detail("maintenance.result", changed);
                                result.require(
                                        TracePluginBridge.counter(changed, "verified") > 0,
                                        "Healthy verify branch not reached");
                                result.require(
                                        TracePluginBridge.counter(changed, "changed") > 0,
                                        "Maintenance changed no history");
                                result.require(
                                        TracePluginBridge.counter(changed, "gaps") > 0,
                                        "Maintenance left no refusal gap");
                                String refusal = trace.rollback(world.getName(), CENTRE, y, CENTRE, RADIUS, 120000);
                                result.detail("maintenance.rollback", refusal);
                                result.require(refusal.startsWith("refused"), "Rollback did not refuse: " + refusal);
                                result.require(
                                        refusal.contains(action.equals("purge") ? "PURGED" : "QUARANTINE"),
                                        "Wrong refusal reason: " + refusal);
                                onEachRegion(context, world, areas, area -> {
                                            for (int[] p : area.positions())
                                                result.require(
                                                        world.getType(p[0], p[1], p[2]) == Material.AIR,
                                                        "Refused rollback changed the world");
                                        })
                                        .whenComplete((ignored, error) -> {
                                            if (error != null)
                                                result.failure("Could not verify refused world: " + error);
                                            else result.detail("maintenance.worldUnchanged", true);
                                            done.complete(null);
                                        });
                            } catch (Exception e) {
                                result.failure("Maintenance scenario failed: " + e);
                                done.complete(null);
                            }
                        });
                    } else
                        Bukkit.getAsyncScheduler()
                                .runNow(
                                        context.plugin(),
                                        task -> interruptAndResume(context, trace, world, areas, y, done));
                })
                .exceptionally(t -> {
                    result.failure("Could not set the scenario up: " + t);
                    done.complete(null);
                    return null;
                });
        return done;
    }

    private void interruptAndResume(
            ScenarioContext context,
            TracePluginBridge trace,
            World world,
            List<Area> areas,
            int y,
            CompletableFuture<Void> done) {
        HarnessResult result = context.result();
        int total = areas.size() * BLOCKS_PER_AREA;
        try {
            // The first operation in a fresh data directory is id 1. Asserted below rather than
            // assumed, because the whole test hangs off cancelling the right one.
            long operationId = 1;
            AtomicBoolean stopSpamming = new AtomicBoolean();
            Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
                // Cancellation is checked at chunk boundaries, and the rollback clears any stale
                // request when it starts. Asking repeatedly means the request is in place by the
                // first boundary, whenever the rollback actually gets going.
                while (!stopSpamming.get()) {
                    try {
                        trace.call("cancelRollback", operationId);
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        return;
                    }
                }
            });

            String interrupted = trace.rollback(world.getName(), CENTRE, y, CENTRE, RADIUS, 120_000);
            stopSpamming.set(true);
            result.detail("rollback.interrupted", interrupted);
            result.require(!interrupted.startsWith("refused"), "The rollback refused: " + interrupted);
            result.require(!interrupted.startsWith("failed"), "The rollback failed: " + interrupted);

            long id = TracePluginBridge.counter(interrupted, "op");
            result.require(id == operationId, "Expected the first operation to be id " + operationId + ", got " + id);
            result.require(
                    interrupted.contains("state=CANCELLED"),
                    "The rollback was not interrupted, so there is nothing to resume: " + interrupted);

            long appliedFirst = TracePluginBridge.counter(interrupted, "applied");
            result.require(appliedFirst > 0, "The interrupted run applied nothing at all: " + interrupted);
            result.require(
                    appliedFirst < total,
                    "The interrupted run finished the whole job (" + appliedFirst + " of " + total
                            + "), so resuming has nothing left to do");

            String unfinished = trace.call("unfinishedRollbacks");
            result.detail("rollback.unfinished", unfinished);
            result.require(
                    unfinished.contains("id=" + operationId),
                    "The interrupted rollback is not listed as unfinished: " + unfinished);

            String resumed = trace.call("resumeRollback", operationId);
            result.detail("rollback.resumed", resumed);
            result.require(!resumed.startsWith("refused"), "The resume refused: " + resumed);
            result.require(!resumed.startsWith("failed"), "The resume failed: " + resumed);
            result.require(resumed.contains("state=DONE"), "The resumed rollback did not finish: " + resumed);

            // This run's numbers, not the operation's. The totals carry the interrupted run's work
            // forward, so asserting on them would pass even if the resume had done nothing.
            long appliedSecond = TracePluginBridge.counter(resumed, "applied");
            long totalApplied = TracePluginBridge.counter(resumed, "totalApplied");
            result.metric("rollback.appliedFirstRun", appliedFirst, "count")
                    .metric("rollback.appliedResumedRun", appliedSecond, "count");
            result.require(appliedSecond > 0, "The resumed run applied nothing, so it resumed nothing: " + resumed);
            result.require(
                    appliedFirst + appliedSecond == total,
                    "The two runs together restored " + (appliedFirst + appliedSecond) + " of " + total);
            result.require(
                    totalApplied == total,
                    "The operation's total says " + totalApplied + " restored, expected " + total);

            String stillUnfinished = trace.call("unfinishedRollbacks");
            result.require(
                    !stillUnfinished.contains("id=" + operationId),
                    "The rollback is still listed as unfinished after completing: " + stillUnfinished);

            verifyWorld(context, world, areas, done);
        } catch (Exception e) {
            result.failure("The resume step failed: " + e);
            done.complete(null);
        }
    }

    private void verifyWorld(ScenarioContext context, World world, List<Area> areas, CompletableFuture<Void> done) {
        HarnessResult result = context.result();
        int[] restored = {0};
        onEachRegion(context, world, areas, area -> {
                    for (int[] position : area.positions()) {
                        Material material = world.getType(position[0], position[1], position[2]);
                        if (material == Material.STONE) {
                            synchronized (restored) {
                                restored[0]++;
                            }
                        } else {
                            result.failure("Position " + position[0] + "," + position[1] + "," + position[2]
                                    + " came back as " + material + " instead of STONE");
                        }
                    }
                })
                .thenRun(() -> {
                    result.metric("world.restored", restored[0], "count").detail("world.verified", true);
                    done.complete(null);
                })
                .exceptionally(t -> {
                    result.failure("Could not verify the world: " + t);
                    done.complete(null);
                    return null;
                });
    }

    /** Completes once every area's region has ticked at least {@code ticks} times. */
    private static CompletableFuture<Void> afterTicks(
            ScenarioContext context, World world, List<Area> areas, long ticks) {
        CompletableFuture<?>[] futures = areas.stream()
                .map(area -> {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    var _ = Bukkit.getRegionScheduler()
                            .runDelayed(
                                    context.plugin(),
                                    world,
                                    area.chunkX(),
                                    area.chunkZ(),
                                    task -> future.complete(null),
                                    ticks);
                    return future;
                })
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Void> onEachRegion(
            ScenarioContext context, World world, List<Area> areas, java.util.function.Consumer<Area> body) {
        CompletableFuture<?>[] futures = areas.stream()
                .map(area -> {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    Bukkit.getRegionScheduler().execute(context.plugin(), world, area.chunkX(), area.chunkZ(), () -> {
                        try {
                            body.accept(area);
                            future.complete(null);
                        } catch (RuntimeException e) {
                            future.completeExceptionally(e);
                        }
                    });
                    return future;
                })
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(futures);
    }

    /** One chunk's worth of blocks to break and put back. */
    private record Area(int chunkX, int chunkZ, List<int[]> positions) {

        static Area at(int chunkX, int chunkZ, int y) {
            List<int[]> positions = new ArrayList<>(BLOCKS_PER_AREA);
            for (int i = 0; i < BLOCKS_PER_AREA; i++) {
                positions.add(new int[] {(chunkX << 4) + (i & 15), y + (i >> 4), (chunkZ << 4) + 1});
            }
            return new Area(chunkX, chunkZ, positions);
        }
    }
}
