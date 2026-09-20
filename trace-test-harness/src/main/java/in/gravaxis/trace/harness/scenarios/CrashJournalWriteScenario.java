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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Breaks blocks continuously, writing down every one, until something kills the server.
 *
 * <p>This is the target the crash rig shoots at: Trace's own capture path, ending in Trace's own
 * journal. The companion {@link CrashJournalVerifyScenario} runs after the restart and asks the one
 * question that matters — of the events written down here, which did Trace lose, and is every one
 * of those inside a recorded gap?
 *
 * <p>Two design points decide whether the test is worth anything.
 *
 * <p><strong>Ground truth is written per event, before the event.</strong> One unbuffered
 * {@code write} each, and no {@code force}: {@code SIGKILL} does not lose a written page, only a
 * power cut would, and forcing thousands of times a second would change what is being measured. The
 * order matters more than the cost — the record has to be on disk before the thing it records, or a
 * loss could just as easily be a truth line that never got written.
 *
 * <p><strong>The staged window is stretched on purpose.</strong> Capture stages records in memory
 * and commits them at tick end, so the only events a kill can take are the ones staged in the tick
 * it lands in. Firing a tick's whole burst in a microsecond would make that window vanishingly
 * unlikely to be hit, and the rig would report success having tested nothing. So the burst is paced
 * across most of the tick, which makes a kill land inside it most of the time — and the rig fails
 * the run outright if no iteration actually lost anything.
 */
public final class CrashJournalWriteScenario implements Scenario {

    static final String TRUTH_FILE = "crash-journal-truth.log";

    /**
     * One fixed-width line: index, the millisecond before the event, and the position.
     *
     * <p>9 + 13 + 7 + 5 + 7 digits, four separators and a newline. Fixed width so the reader can
     * tell a torn tail from a corrupt file, and checked on every write so the two can never drift
     * apart unnoticed.
     */
    static final int RECORD_BYTES = 46;

    /** Two areas, far enough apart to be separate regions on a regionised server. */
    static final int[][] AREA_CHUNKS = {{0, 0}, {128, 128}};

    /** Events an area stages in one tick. Below the staging buffer, so none publish unconfirmed. */
    private static final int EVENTS_PER_TICK = 600;

    /** How much of a 50 ms tick the burst is spread over. */
    private static final long BURST_NANOS = 35_000_000L;

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        String worldName = context.param("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            result.failure("No world named '" + worldName + "'");
            return CompletableFuture.completedFuture(null);
        }
        if (TracePluginBridge.find() == null) {
            result.failure("Trace is not installed, so there is nothing to crash");
            return CompletableFuture.completedFuture(null);
        }

        int yBase = world.getMinHeight() + 10;
        result.detail("crash.truthFile", TRUTH_FILE)
                .detail("crash.eventsPerTick", EVENTS_PER_TICK)
                .detail("crash.areas", AREA_CHUNKS.length)
                .detail("crash.yBase", yBase);

        Path truth = context.workingDirectory().resolve(TRUTH_FILE);
        FileChannel channel;
        try {
            channel = FileChannel.open(
                    truth, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            result.failure("Could not open the ground-truth file: " + e);
            return CompletableFuture.completedFuture(null);
        }

        AtomicInteger nextIndex = new AtomicInteger();
        AtomicInteger ready = new AtomicInteger();

        for (int[] area : AREA_CHUNKS) {
            int chunkX = area[0];
            int chunkZ = area[1];
            var _ = world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, failure) -> {
                if (failure != null) {
                    result.failure("Could not load chunk " + chunkX + "," + chunkZ + ": " + failure);
                    return;
                }
                // On the region thread that owns the chunk: taking a ticket and scheduling belong
                // to its owner. An earlier version did this on whatever thread the load completed
                // on, and the exception went into the future and was never seen, so the scenario
                // silently did nothing at all for five minutes.
                Bukkit.getRegionScheduler().execute(context.plugin(), world, chunkX, chunkZ, () -> {
                    try {
                        // A ticket, because nothing else keeps a chunk 2048 blocks from spawn
                        // loaded for a long fixed-rate task at this server's view distance.
                        world.addPluginChunkTicket(chunkX, chunkZ, context.plugin());
                        var _ = Bukkit.getRegionScheduler()
                                .runAtFixedRate(
                                        context.plugin(),
                                        world,
                                        chunkX,
                                        chunkZ,
                                        task -> burst(context, world, channel, chunkX, chunkZ, yBase, nextIndex, ready),
                                        1L,
                                        1L);
                        context.logger().info("Capturing in chunk {},{}", chunkX, chunkZ);
                    } catch (RuntimeException e) {
                        result.failure("Could not start capturing in chunk " + chunkX + "," + chunkZ + ": " + e);
                    }
                });
            });
        }

        // Never completes: the rig kills the server. That is the scenario.
        return new CompletableFuture<>();
    }

    private void burst(
            ScenarioContext context,
            World world,
            FileChannel channel,
            int chunkX,
            int chunkZ,
            int yBase,
            AtomicInteger nextIndex,
            AtomicInteger ready) {
        int maxY = world.getMaxHeight() - 1;
        if (ready.get() == 0) {
            recordRegionOwnership(context, world, chunkX, chunkZ);
        }

        ByteBuffer line = ByteBuffer.allocateDirect(RECORD_BYTES);
        long startedAt = System.nanoTime();
        int fired = 0;
        try {
            for (int i = 0; i < EVENTS_PER_TICK; i++) {
                int index = nextIndex.getAndIncrement();
                int x = (chunkX << 4) + (index & 15);
                int z = (chunkZ << 4) + ((index >>> 4) & 15);
                // Climbing, never wrapping: every event in a run gets a position of its own. When
                // positions repeat, an event that was lost is indistinguishable from one that was
                // kept at the same place, and the verification silently undercounts the loss it
                // exists to measure. An earlier version wrapped at 64 layers and did exactly that.
                int y = yBase + (index >>> 8);
                if (y > maxY) {
                    context.result().detail("crash.ranOutOfHeight", "stopped at index " + index + ", y would be " + y);
                    return;
                }

                Block block = world.getBlockAt(x, y, z);
                if (block.getType() != Material.STONE) {
                    block.setType(Material.STONE, false);
                }

                long before = System.currentTimeMillis();
                // Written before the event, so a line in this file always describes something the
                // server was about to do rather than something it might not have reached.
                write(channel, line, index, before, x, y, z);
                HarnessPlayers.fireBlockBreak(block);
                block.setType(Material.AIR, false);
                fired++;

                // Paced, so the staged window covers most of the tick. See the class comment.
                long target = startedAt + (BURST_NANOS * (i + 1)) / EVENTS_PER_TICK;
                while (System.nanoTime() < target) {
                    Thread.onSpinWait();
                }
            }
        } catch (IOException | RuntimeException e) {
            context.result().failure("The burst stopped after " + fired + " events: " + e);
            context.logger().error("Burst in chunk {},{} failed", chunkX, chunkZ, e);
            return;
        }

        if (ready.compareAndSet(0, 1)) {
            // Only now is Trace genuinely capturing. This is what arms the kill.
            context.announceReady("crash-journal-write file=" + TRUTH_FILE);
        }
    }

    /**
     * Records whether the two areas really are separate regions.
     *
     * <p>Asserted rather than assumed: the Folia run's entire reason to exist is that capture is
     * happening on two region threads at once, and a run where both areas landed in one region
     * would look identical without this.
     */
    private void recordRegionOwnership(ScenarioContext context, World world, int chunkX, int chunkZ) {
        for (int[] other : AREA_CHUNKS) {
            if (other[0] == chunkX) {
                continue;
            }
            boolean sameRegion = Bukkit.isOwnedByCurrentRegion(world, other[0], other[1]);
            context.result().detail("crash.ownsTheOtherArea." + chunkX + "," + chunkZ, sameRegion);
            if (regionised() && sameRegion) {
                context.result()
                        .failure("The two areas are one region, so this run does not exercise capture across"
                                + " regions");
            }
        }
    }

    private static boolean regionised() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
    }

    private static void write(FileChannel channel, ByteBuffer line, int index, long before, int x, int y, int z)
            throws IOException {
        String text = "%09d %013d %07d %05d %07d\n".formatted(index, before, x, y, z);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != RECORD_BYTES) {
            throw new IOException("Ground-truth record is " + bytes.length + " bytes, expected " + RECORD_BYTES);
        }
        line.clear();
        line.put(bytes);
        line.flip();
        // Looped, because a short write would desynchronise a fixed-width reader.
        while (line.hasRemaining()) {
            channel.write(line);
        }
    }
}
