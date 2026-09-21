/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import in.gravaxis.trace.harness.TracePluginBridge;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Separates wire acknowledgements, observed events, tick-end world state and stored history.
 * A successful socket alone cannot close ADR-0014's synthetic-event limitation.
 */
public final class ClientCaptureScenario implements Scenario {
    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        var done = new CompletableFuture<Void>();
        World world = Bukkit.getWorlds().getFirst();
        var observer = new Observer();
        Bukkit.getPluginManager().registerEvents(observer, context.plugin());
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
            try {
                TracePluginBridge bridge = TracePluginBridge.find();
                if (bridge == null) throw new IllegalStateException("Trace bridge absent");
                world.getChunkAtAsync(0, 0, true).get(30, TimeUnit.SECONDS);
                world.getChunkAtAsync(128, 128, true).get(30, TimeUnit.SECONDS);
                boolean regionised = io.papermc.paper.ServerBuildInfo.buildInfo()
                        .isBrandCompatible(net.kyori.adventure.key.Key.key("papermc", "folia"));
                for (int area = 0; area < 2; area++) {
                    int base = area * 2048;
                    observer.base = base;
                    observer.joined = new CompletableFuture<>();
                    observer.quit = new CompletableFuture<>();
                    onRegion(context, world, base, () -> {
                        check(Bukkit.isOwnedByCurrentRegion(world, base >> 4, base >> 4), "Fixture owns its region");
                        if (regionised)
                            check(
                                    !Bukkit.isOwnedByCurrentRegion(world, (2048 - base) >> 4, (2048 - base) >> 4),
                                    "Folia areas really differ");
                        for (int x = base; x <= base + 5; x++) {
                            world.getBlockAt(x, 79, base).setType(Material.STONE, false);
                            world.getBlockAt(x, 80, base).setType(Material.AIR, false);
                            world.getBlockAt(x, 81, base).setType(Material.AIR, false);
                        }
                        world.getBlockAt(base, 80, base).setType(Material.STONE, false);
                        world.getBlockAt(base + 1, 80, base).setType(Material.STONE, false);
                    });
                    long started = System.currentTimeMillis();
                    String before = bridge.captureCounters();
                    try (var client = new LoopbackClient(Bukkit.getPort(), "TraceBot" + area)) {
                        Player player = observer.joined.get(30, TimeUnit.SECONDS);
                        client.awaitReady();
                        int teleports = client.teleports();
                        check(
                                player.teleportAsync(new Location(world, base + 2.5, 80, base + 2.5))
                                        .get(30, TimeUnit.SECONDS),
                                "Teleport completed");
                        client.awaitTeleportAfter(teleports);
                        onRegion(context, world, base, () -> {
                            check(Bukkit.isOwnedByCurrentRegion(player), "Fixture owns player");
                            player.setGameMode(GameMode.CREATIVE);
                            player.getInventory().setItemInMainHand(new ItemStack(Material.DIRT));
                        });
                        client.breakBlock(base, 80, base, 1);
                        client.awaitAcknowledged(1);
                        onRegion(
                                context,
                                world,
                                base,
                                () -> check(
                                        world.getType(base, 80, base) == Material.AIR, "Accepted break changed world"));
                        verifyCounters(before, bridge.captureCounters(), 1, 1, 0);
                        client.placeBlock(base + 2, 80, base, 2);
                        client.awaitAcknowledged(2);
                        onRegion(
                                context,
                                world,
                                base,
                                () -> check(
                                        world.getType(base + 2, 80, base) == Material.DIRT,
                                        "Accepted place changed world"));
                        verifyCounters(before, bridge.captureCounters(), 2, 2, 0);
                        client.breakBlock(base + 1, 80, base, 3);
                        client.awaitAcknowledged(3);
                        onRegion(
                                context,
                                world,
                                base,
                                () -> check(
                                        world.getType(base + 1, 80, base) == Material.STONE,
                                        "Cancelled break preserved world"));
                        verifyCounters(before, bridge.captureCounters(), 2, 2, 0);
                        client.breakBlock(base + 4, 80, base, 4);
                        client.awaitAcknowledged(4);
                        onRegion(
                                context,
                                world,
                                base,
                                () -> check(
                                        world.getType(base + 4, 80, base) == Material.AIR,
                                        "Unchanged air attempt preserved world"));
                        verifyCounters(before, bridge.captureCounters(), 3, 2, 1);
                        client.placeBlock(base + 3, 80, base, 5);
                        client.awaitAcknowledged(5);
                        onRegion(
                                context,
                                world,
                                base,
                                () -> check(
                                        world.getType(base + 3, 80, base) == Material.AIR,
                                        "Same-tick revert preserved original state"));
                        verifyCounters(before, bridge.captureCounters(), 4, 2, 2);
                        String rows = bridge.call("clientCaptureRows", world.getName(), base, 80, base);
                        verifyRows(rows, base, player.getUniqueId().toString(), started);
                        String after = bridge.captureCounters();
                        for (String counter : new String[] {"captured", "published", "rejectedUnchanged", "dropped"}) {
                            long delta = TracePluginBridge.counter(after, counter)
                                    - TracePluginBridge.counter(before, counter);
                            long expected =
                                    switch (counter) {
                                        case "captured" -> 4;
                                        case "published" -> 2;
                                        case "rejectedUnchanged" -> 2;
                                        default -> 0;
                                    };
                            check(
                                    delta == expected,
                                    counter + " expected " + expected + " got " + delta + "; " + after);
                            context.result().metric("area" + area + "." + counter, (double) delta, "records");
                        }
                        context.result().detail("area" + area + ".rows", rows);
                        context.result().metric("area" + area + ".acknowledgedActions", 5, "actions");
                    }
                    observer.quit.get(30, TimeUnit.SECONDS);
                }
                check(
                        observer.breaks.get() == 6 && observer.cancelled.get() == 2 && observer.unchanged.get() == 2,
                        "Accepted and cancelled natural break events");
                check(
                        observer.places.get() == 4 && observer.reverted.get() == 2,
                        "Accepted and reverted natural place events");
                context.result().metric("natural.breakEvents", observer.breaks.get(), "events");
                context.result().metric("natural.placeEvents", observer.places.get(), "events");
                context.result().metric("cancelled.events", observer.cancelled.get(), "events");
                context.result().metric("reverted.events", observer.reverted.get(), "events");
                context.result().metric("unchanged.events", observer.unchanged.get(), "events");
                context.result()
                        .detail(
                                "client",
                                "Real loopback TCP, in-process peer using pinned server codecs; no synthetic callEvent");
                context.result()
                        .detail(
                                "limits",
                                "Offline creative fixture; material-level capture; air attempt fires a natural event rejected at tick end. Shared codecs do not independently verify protocol. No production performance claim.");
                context.result().detail("proof.complete", true);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            } finally {
                HandlerList.unregisterAll(observer);
            }
        });
        return done;
    }

    static void verifyCounters(String before, String after, long captured, long published, long rejected) {
        String[] names = {"captured", "published", "rejectedUnchanged", "dropped", "outOfRange", "unconfirmed"};
        long[] expected = {captured, published, rejected, 0, 0, 0};
        for (int i = 0; i < names.length; i++) {
            long initial = TracePluginBridge.counter(before, names[i]);
            long current = TracePluginBridge.counter(after, names[i]);
            check(
                    initial >= 0 && current >= 0 && current - initial == expected[i],
                    "Branch mismatch " + names[i] + ": " + before + " -> " + after);
        }
    }

    static void verifyRows(String rows, int base, String actor, long started) {
        Set<String> expected = Set.of(
                base + ":80:" + base + ":STONE:AIR:" + actor + ":1:1",
                (base + 2) + ":80:" + base + ":AIR:DIRT:" + actor + ":2:1");
        Set<String> actual = new HashSet<>();
        Set<String> identities = new HashSet<>();
        String[] records = rows.split(";");
        check(records.length == 2, "Exactly the two real mutations: " + rows);
        for (String row : records) {
            String[] fields = row.split(":");
            check(fields.length == 11, "Complete row fields");
            long timestamp = Long.parseLong(fields[8]);
            int sequence = Integer.parseInt(fields[9]);
            check(timestamp >= started && timestamp <= System.currentTimeMillis() + 2, "Captured timestamp bounds");
            check(sequence >= 0 && sequence <= 65535, "Sequence in packed range");
            long expectedChunk = (3L << 42) | (base == 0 ? 0 : 3L << 14);
            check(Long.parseLong(fields[10]) == expectedChunk, "Stored chunk identity");
            check(identities.add(fields[10] + ":" + timestamp + ":" + sequence), "Unique stored identities");
            actual.add(String.join(":", java.util.Arrays.copyOf(fields, 8)));
        }
        check(actual.equals(expected), "Full mutation content matches: " + rows);
    }

    private static void onRegion(ScenarioContext context, World world, int base, Runnable action) throws Exception {
        var barrier = new CompletableFuture<Void>();
        Bukkit.getRegionScheduler()
                .runDelayed(
                        context.plugin(),
                        world,
                        base >> 4,
                        base >> 4,
                        task -> {
                            try {
                                action.run();
                                barrier.complete(null);
                            } catch (Throwable e) {
                                barrier.completeExceptionally(e);
                            }
                        },
                        2);
        barrier.get(30, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Observer implements Listener {
        volatile int base;
        volatile CompletableFuture<Player> joined = new CompletableFuture<>();
        volatile CompletableFuture<Void> quit = new CompletableFuture<>();
        final AtomicInteger breaks = new AtomicInteger();
        final AtomicInteger places = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicInteger reverted = new AtomicInteger();
        final AtomicInteger unchanged = new AtomicInteger();

        @EventHandler
        public void join(PlayerJoinEvent event) {
            if (event.getPlayer().getName().startsWith("TraceBot")) joined.complete(event.getPlayer());
        }

        @EventHandler
        public void quit(PlayerQuitEvent event) {
            if (event.getPlayer().getName().startsWith("TraceBot")) quit.complete(null);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void breaking(BlockBreakEvent event) {
            if (!event.getPlayer().getName().startsWith("TraceBot")) return;
            breaks.incrementAndGet();
            if (event.getBlock().getX() == base + 4) unchanged.incrementAndGet();
            if (event.getBlock().getX() == base + 1) {
                event.setCancelled(true);
                cancelled.incrementAndGet();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void placing(BlockPlaceEvent event) {
            if (!event.getPlayer().getName().startsWith("TraceBot")) return;
            places.incrementAndGet();
            if (event.getBlock().getX() == base + 3) {
                // Deliberately adversarial later listener: Trace must confirm the actual tick-end state.
                event.getBlock().setType(Material.AIR, false);
                reverted.incrementAndGet();
            }
        }
    }
}
