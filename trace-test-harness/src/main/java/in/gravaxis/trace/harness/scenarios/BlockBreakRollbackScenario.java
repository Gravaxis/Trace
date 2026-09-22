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
import io.papermc.paper.ServerBuildInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The M2 definition of done, end to end: block changes are captured, stored, and put back.
 *
 * <p>Five things are asserted, and three of them are about not doing the wrong thing:
 *
 * <ol>
 *   <li>broken blocks come back and placed blocks go away again;
 *   <li>a block someone changed <em>after</em> the history being undone is left alone and counted,
 *       not clobbered;
 *   <li>events that changed nothing — the reported lava-punch case, where a client mod fires break
 *       events at a block that never breaks — are not recorded at all (ADR-0014);
 *   <li>Trace's own rollback writes are themselves recorded, so the rollback can be undone;
 *   <li>and all of it holds across two work areas 640 blocks apart, which a regionised server owns
 *       on two different threads — the case where a single-threaded assumption would show up.
 * </ol>
 *
 * <p>Breaks are driven by firing the event and performing the removal, because no player is
 * connected on a CI server. That is a real limitation of this scenario and it is reported in the
 * result: it exercises Trace's listener, pipeline, storage and rollback, but not the server's own
 * break path. A protocol-level client is the M4 answer.
 */
public final class BlockBreakRollbackScenario implements Scenario {

    /**
     * Two work areas, far enough apart that a regionised server owns them separately.
     *
     * <p>128 chunks is measured, not guessed. Asking Folia 26.2 build 7 (default {@code
     * grid-exponent: 4}) which chunks its region owned put the boundary between 64 and 128 chunks
     * away; an earlier version of this scenario used 40 and quietly tested one region twice.
     */
    private static final int[][] AREA_CHUNKS = {{0, 0}, {128, 128}};

    private static final int BREAKS_PER_AREA = 12;
    private static final int PLACES_PER_AREA = 6;
    private static final Material PLACED = Material.OAK_PLANKS;

    /** A centre and radius whose box reaches both areas. */
    private static final int CENTRE = 1024;

    private static final int RADIUS = 1100;

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

        int y = world.getMinHeight() + 10;
        List<Area> areas = new ArrayList<>();
        for (int i = 0; i < AREA_CHUNKS.length; i++) {
            areas.add(Area.at(AREA_CHUNKS[i][0], AREA_CHUNKS[i][1], y, i == 0));
        }
        Area first = areas.getFirst();

        result.detail("capture.path", "synthetic BlockBreakEvent/BlockPlaceEvent plus the world change, no client")
                .detail("areas", AREA_CHUNKS.length)
                .detail("blocks.broken", areas.size() * BREAKS_PER_AREA)
                .detail("blocks.placed", areas.size() * PLACES_PER_AREA);

        CompletableFuture<Void> done = new CompletableFuture<>();
        allOf(
                        areas,
                        area -> world.getChunkAtAsync(area.chunkX(), area.chunkZ(), true)
                                .thenApply(chunk -> Boolean.TRUE))
                .thenCompose(loaded -> allOf(
                        areas,
                        area -> onRegion(context, world, area, 0, task -> {
                            result.require(
                                    world.addPluginChunkTicket(area.chunkX(), area.chunkZ(), context.plugin()),
                                    "Fixture retains its playerless chunk through confirmation");
                            prepare(world, area);
                        })))
                .thenCompose(prepared -> {
                    result.detail("history.startedAt", System.currentTimeMillis());
                    return allOf(
                            areas,
                            area -> onRegion(context, world, area, 5, task -> makeHistory(context, world, area)));
                })
                .thenCompose(history -> onRegion(context, world, first, 5, task -> {
                    // Someone edits one of the positions after the fact. A rollback must leave
                    // their work alone.
                    int[] tampered = first.breaks().getFirst();
                    world.getBlockAt(tampered[0], tampered[1], tampered[2]).setType(Material.DIAMOND_BLOCK, false);
                }))
                .thenRun(() -> Bukkit.getAsyncScheduler()
                        .runNow(context.plugin(), async -> finish(context, trace, world, areas, done)))
                .exceptionally(t -> {
                    result.failure("Could not set the scenario up: " + t);
                    done.complete(null);
                    return null;
                });
        return done;
    }

    /** Lays out an area: solid blocks to break, empty space to place into, one block that will not break. */
    private void prepare(World world, Area area) {
        for (int[] position : area.breaks()) {
            world.getBlockAt(position[0], position[1], position[2]).setType(Material.STONE, false);
        }
        for (int[] position : area.places()) {
            world.getBlockAt(position[0], position[1], position[2]).setType(Material.AIR, false);
        }
        int[] unbreakable = area.unbreakable();
        world.getBlockAt(unbreakable[0], unbreakable[1], unbreakable[2]).setType(Material.BEDROCK, false);
    }

    private void makeHistory(ScenarioContext context, World world, Area area) {
        for (int[] position : area.breaks()) {
            Block block = world.getBlockAt(position[0], position[1], position[2]);
            HarnessPlayers.fireBlockBreak(block);
            block.setType(Material.AIR, false);
        }
        for (int[] position : area.places()) {
            HarnessPlayers.fireBlockPlace(world.getBlockAt(position[0], position[1], position[2]), PLACED);
        }
        fireFailingBreaks(context, world, area.unbreakable());
        context.logger()
                .info(
                        "Harness broke {} and placed {} blocks in chunk {},{}",
                        area.breaks().size(),
                        area.places().size(),
                        area.chunkX(),
                        area.chunkZ());
    }

    /**
     * Fires break events at a block that does not break — the reported defect.
     *
     * <p>A logger that records events rather than changes writes one row per attempt, and the area's
     * history then shows breaks that never happened.
     */
    private void fireFailingBreaks(ScenarioContext context, World world, int[] position) {
        Block block = world.getBlockAt(position[0], position[1], position[2]);
        for (int i = 0; i < 5; i++) {
            HarnessPlayers.fireBlockBreak(block);
        }
        context.logger().info("Harness fired 5 break events at a block it did not break");
    }

    private void finish(
            ScenarioContext context,
            TracePluginBridge trace,
            World world,
            List<Area> areas,
            CompletableFuture<Void> done) {
        HarnessResult result = context.result();
        try {
            int failing = 5 * areas.size();
            int changes = areas.size() * (BREAKS_PER_AREA + PLACES_PER_AREA);

            String counters = trace.captureCounters();
            result.detail("counters.beforeRollback", counters);
            long rejected = TracePluginBridge.counter(counters, "rejectedUnchanged");
            result.require(
                    rejected >= failing,
                    "Break events that changed nothing were recorded as history (rejectedUnchanged=" + rejected
                            + ", expected at least " + failing + "). See ADR-0014.");

            int y = areas.getFirst().breaks().getFirst()[1];
            String summary = trace.rollback(world.getName(), CENTRE, y, CENTRE, RADIUS, 120_000);
            result.detail("rollback", summary);
            result.require(!summary.startsWith("refused"), "The rollback refused: " + summary);
            result.require(!summary.startsWith("failed"), "The rollback failed: " + summary);

            long applied = TracePluginBridge.counter(summary, "applied");
            long mismatched = TracePluginBridge.counter(summary, "mismatched");
            long chunks = TracePluginBridge.counter(summary, "chunks");
            result.metric("rollback.applied", (double) applied, "count")
                    .metric("rollback.mismatched", (double) mismatched, "count")
                    .metric("rollback.chunks", (double) chunks, "count");
            result.require(applied == changes - 1, "Expected " + (changes - 1) + " positions restored, got " + applied);
            result.require(
                    mismatched == 1, "Expected exactly one position to be skipped as changed-since, got " + mismatched);
            result.require(chunks == areas.size(), "Expected " + areas.size() + " chunks touched, got " + chunks);

            verifyWorld(context, world, areas, done);
        } catch (Exception e) {
            result.failure("Rollback step failed: " + e);
            done.complete(null);
        }
    }

    private void verifyWorld(ScenarioContext context, World world, List<Area> areas, CompletableFuture<Void> done) {
        HarnessResult result = context.result();
        Map<String, String> owners = new LinkedHashMap<>();
        Map<String, Boolean> ownsTheOther = new LinkedHashMap<>();
        int[] restored = {0};
        int[] cleared = {0};

        allOf(
                        areas,
                        area -> onRegion(context, world, area, 0, task -> {
                            // Whether this area's owning region also owns the other area. Asked from
                            // inside the region task, this is the server's own answer about
                            // ownership, which is the claim being made. Thread names cannot answer
                            // it: Folia services regions from a pool, so two regions often share one
                            // thread, and an earlier version of this check failed for that reason
                            // alone.
                            Area other = areas.stream()
                                    .filter(candidate -> candidate.chunkX() != area.chunkX())
                                    .findFirst()
                                    .orElseThrow();
                            boolean sameRegion = Bukkit.isOwnedByCurrentRegion(world, other.chunkX(), other.chunkZ());
                            String key = area.chunkX() + "," + area.chunkZ();
                            synchronized (owners) {
                                owners.put(key, Thread.currentThread().getName());
                                ownsTheOther.put(key, sameRegion);
                            }
                            List<int[]> breaks = area.breaks();
                            for (int i = area.primary() ? 1 : 0; i < breaks.size(); i++) {
                                int[] position = breaks.get(i);
                                Material material = world.getType(position[0], position[1], position[2]);
                                if (material == Material.STONE) {
                                    restored[0]++;
                                } else {
                                    result.failure("Broken block at " + describe(position) + " came back as " + material
                                            + " instead of STONE");
                                }
                            }
                            for (int[] position : area.places()) {
                                Material material = world.getType(position[0], position[1], position[2]);
                                if (material == Material.AIR) {
                                    cleared[0]++;
                                } else {
                                    result.failure("Placed block at " + describe(position) + " is still " + material
                                            + " after the rollback");
                                }
                            }
                            if (area.primary()) {
                                int[] tampered = breaks.getFirst();
                                Material now = world.getType(tampered[0], tampered[1], tampered[2]);
                                result.require(
                                        now == Material.DIAMOND_BLOCK,
                                        "The rollback overwrote a block that was changed after the history it undid (found "
                                                + now + ")");
                            }
                            int[] unbreakable = area.unbreakable();
                            Material stillThere = world.getType(unbreakable[0], unbreakable[1], unbreakable[2]);
                            result.require(
                                    stillThere == Material.BEDROCK,
                                    "The block that never broke was altered by the rollback (found " + stillThere
                                            + ")");
                        }))
                .thenRun(() -> {
                    result.metric("world.restored", restored[0], "count")
                            .metric("world.cleared", cleared[0], "count")
                            .detail("world.threads", owners)
                            .detail("world.ownsTheOtherArea", ownsTheOther)
                            .detail("world.verified", true);
                    if (regionised()) {
                        result.require(
                                ownsTheOther.values().stream().noneMatch(Boolean::booleanValue),
                                "The two areas turned out to be one region, so this run did not exercise a rollback"
                                        + " across regions: " + ownsTheOther);
                    }
                    done.complete(null);
                })
                .exceptionally(t -> {
                    result.failure("Could not verify the world: " + t);
                    done.complete(null);
                    return null;
                });
    }

    private static boolean regionised() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
    }

    private static String describe(int[] position) {
        return position[0] + "," + position[1] + "," + position[2];
    }

    /** Runs a task on the thread that owns an area, as a future. */
    private static CompletableFuture<Void> onRegion(
            ScenarioContext context, World world, Area area, long delayTicks, Consumer<Area> body) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable run = () -> {
            try {
                body.accept(area);
                future.complete(null);
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        };
        if (delayTicks <= 0) {
            Bukkit.getRegionScheduler().run(context.plugin(), world, area.chunkX(), area.chunkZ(), task -> run.run());
        } else {
            Bukkit.getRegionScheduler()
                    .runDelayed(context.plugin(), world, area.chunkX(), area.chunkZ(), task -> run.run(), delayTicks);
        }
        return future;
    }

    private static <T> CompletableFuture<Void> allOf(
            List<Area> areas, java.util.function.Function<Area, CompletableFuture<T>> start) {
        CompletableFuture<?>[] futures = areas.stream().map(start).toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(futures);
    }

    /**
     * One work area: a chunk, the positions broken in it, and the positions placed into.
     *
     * <p>The primary area carries the extras that only need testing once — the block tampered with
     * after the fact, and the assertion about it.
     */
    private record Area(int chunkX, int chunkZ, int y, List<int[]> breaks, List<int[]> places, boolean primary) {

        static Area at(int chunkX, int chunkZ, int y, boolean primary) {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            List<int[]> breaks = new ArrayList<>();
            for (int i = 0; i < BREAKS_PER_AREA; i++) {
                breaks.add(new int[] {baseX + i, y, baseZ});
            }
            List<int[]> places = new ArrayList<>();
            for (int i = 0; i < PLACES_PER_AREA; i++) {
                places.add(new int[] {baseX + i, y, baseZ + 1});
            }
            return new Area(chunkX, chunkZ, y, breaks, places, primary);
        }

        /** The corner block that will be hit repeatedly and never break. */
        int[] unbreakable() {
            return new int[] {(chunkX << 4) + 15, y, (chunkZ << 4) + 15};
        }
    }
}
