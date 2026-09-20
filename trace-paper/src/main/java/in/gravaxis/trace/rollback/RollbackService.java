/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.rollback;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.core.record.RecordKind;
import in.gravaxis.trace.dictionary.ActorDictionary;
import in.gravaxis.trace.dictionary.BlockStateDictionary;
import in.gravaxis.trace.dictionary.WorldDictionary;
import in.gravaxis.trace.pipeline.StoreConsumer;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Puts the world back, one chunk at a time, without ever holding the whole job in memory.
 *
 * <p>The shape of this class is the product: rows are streamed newest-first, folded per chunk, and
 * applied on the thread that owns that chunk. Nothing accumulates, so rolling back fifty million
 * edits costs the same heap as rolling back twenty-seven thousand.
 *
 * <p>Two refusals and one skip keep it honest:
 *
 * <ul>
 *   <li>if any events were lost in the requested window, the rollback <strong>refuses</strong> —
 *       a partly-correct world is worse than an honest no;
 *   <li>if a position no longer looks the way the history says it was left, it is skipped and
 *       counted, never overwritten, so a rollback on a live server cannot clobber a later edit;
 *   <li>and every write Trace makes is itself captured, with Trace as the actor, so the rollback
 *       can be undone in turn.
 * </ul>
 */
public final class RollbackService {

    private static final long FLUSH_TIMEOUT_MILLIS = 30_000;
    private static final long CHUNK_APPLY_TIMEOUT_MILLIS = 30_000;
    private static final int BATCH_SIZE = 4096;

    private final Plugin plugin;
    private final EventStore store;
    private final StoreConsumer consumer;
    private final CaptureService capture;
    private final WorldDictionary worlds;
    private final BlockStateDictionary states;

    public RollbackService(
            Plugin plugin,
            EventStore store,
            StoreConsumer consumer,
            CaptureService capture,
            WorldDictionary worlds,
            BlockStateDictionary states) {
        this.plugin = plugin;
        this.store = store;
        this.consumer = consumer;
        this.capture = capture;
        this.worlds = worlds;
        this.states = states;
    }

    /**
     * Rolls back every recorded change in a box and time window.
     *
     * <p>Blocking, and must not be called from a tick thread: it waits for storage and for region
     * tasks. The caller runs it on the async scheduler.
     */
    public RollbackSummary rollback(World world, BlockBox box, long fromMillis, long toMillis) throws StoreException {
        int worldId = worlds.idOf(world);
        if (worldId < 0) {
            return RollbackSummary.refused("Trace has no history for the world " + world.getName());
        }

        // Rows must stop moving before they are planned over: sealing first is also what makes an
        // interrupted rollback resumable later.
        consumer.flushAndSeal(FLUSH_TIMEOUT_MILLIS);

        List<GapRecord> gaps = store.gapsBetween(fromMillis, toMillis);
        if (!gaps.isEmpty()) {
            GapRecord first = gaps.get(0);
            return RollbackSummary.refused(
                    "Events were lost between " + first.fromMillis() + " and " + first.toMillis() + " ("
                            + first.reason() + "). Rolling back across a gap would produce a world that never"
                            + " existed, so Trace will not do it.");
        }

        ScanPlan plan = ScanPlan.of(worldId, box, fromMillis, toMillis, ScanPlan.Order.NEWEST_FIRST)
                .withBatchSize(BATCH_SIZE);

        ChunkFold fold = new ChunkFold();
        RollbackSummary.Builder summary = RollbackSummary.builder();
        MutationBatch batch = new MutationBatch(BATCH_SIZE);

        try (MutationCursor cursor = store.scan(plan)) {
            long currentChunk = Long.MIN_VALUE;
            boolean any = false;
            while (cursor.next(batch)) {
                for (int i = 0; i < batch.size(); i++) {
                    summary.scanned();
                    long chunkKey = batch.chunkKey(i);
                    if (any && chunkKey != currentChunk) {
                        applyChunk(world, currentChunk, fold, summary);
                        fold.clear();
                    }
                    currentChunk = chunkKey;
                    any = true;
                    fold.accept(batch.x(i), batch.y(i), batch.z(i), batch.afterState(i), batch.beforeState(i));
                }
            }
            if (any) {
                applyChunk(world, currentChunk, fold, summary);
            }
        }
        return summary.build();
    }

    private void applyChunk(World world, long chunkKey, ChunkFold fold, RollbackSummary.Builder summary) {
        int chunkX = Morton.chunkX(chunkKey);
        int chunkZ = Morton.chunkZ(chunkKey);
        int worldId = worlds.idOf(world);

        // Loaded before the region task runs: a synchronous chunk load on a tick thread is one of
        // the ways a rollback turns into a server freeze.
        world.getChunkAtAsync(chunkX, chunkZ, true).join();

        CompletableFuture<Void> applied = new CompletableFuture<>();
        int count = fold.size();
        int[] positions = fold.positions();
        int[] expected = fold.expected();
        int[] targets = fold.targets();

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;
                for (int i = 0; i < count; i++) {
                    int x = baseX + ChunkFold.localX(positions[i]);
                    int y = ChunkFold.y(positions[i]);
                    int z = baseZ + ChunkFold.localZ(positions[i]);
                    int current = states.idOf(world.getType(x, y, z));
                    if (current == targets[i]) {
                        summary.alreadyThere();
                        continue;
                    }
                    if (current != expected[i]) {
                        // Someone changed this block after the history Trace is undoing. Their work
                        // is not ours to overwrite.
                        summary.mismatched();
                        continue;
                    }
                    Material material = states.materialOf(targets[i]);
                    if (material == null) {
                        summary.mismatched();
                        continue;
                    }
                    // Trace's own write is captured like any other change, so it can be undone.
                    capture.captureBlockChange(
                            worldId,
                            x,
                            y,
                            z,
                            current,
                            ActorDictionary.TRACE,
                            Cause.ROLLBACK.id(),
                            RecordKind.BLOCK.id());
                    world.getBlockAt(x, y, z).setType(material, false);
                    summary.applied();
                }
                applied.complete(null);
            } catch (RuntimeException e) {
                applied.completeExceptionally(e);
            }
        });

        try {
            applied.get(CHUNK_APPLY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            summary.chunk();
        } catch (Exception e) {
            throw new IllegalStateException("Applying chunk " + chunkX + "," + chunkZ + " failed", e);
        }
    }

    /**
     * The history of one chunk, folded into what to do at each position.
     *
     * <p>Rows arrive newest first, so the first row seen for a position says what the world should
     * look like now, and the last one says what to restore. Memory is one chunk's worth of
     * positions, which is what keeps the whole rollback flat.
     */
    static final class ChunkFold {

        private static final int INITIAL = 1024;
        private static final int Y_BIAS = 2048;

        private int[] positions = new int[INITIAL];
        private int[] expected = new int[INITIAL];
        private int[] targets = new int[INITIAL];
        /** Open-addressing index: packed position to (slot + 1), zero meaning empty. */
        private int[] index = new int[INITIAL * 2];

        private int size;

        void accept(int x, int y, int z, int afterState, int beforeState) {
            int packed = pack(x, y, z);
            int mask = index.length - 1;
            int probe = mix(packed) & mask;
            while (index[probe] != 0) {
                int slot = index[probe] - 1;
                if (positions[slot] == packed) {
                    // An older row for a position already seen: it holds the earlier state, which
                    // is the one to restore.
                    targets[slot] = beforeState;
                    return;
                }
                probe = (probe + 1) & mask;
            }
            if (size == positions.length) {
                grow();
                accept(x, y, z, afterState, beforeState);
                return;
            }
            positions[size] = packed;
            expected[size] = afterState;
            targets[size] = beforeState;
            index[probe] = ++size;
        }

        int size() {
            return size;
        }

        int[] positions() {
            return positions;
        }

        int[] expected() {
            return expected;
        }

        int[] targets() {
            return targets;
        }

        void clear() {
            size = 0;
            java.util.Arrays.fill(index, 0);
        }

        private void grow() {
            int capacity = positions.length * 2;
            positions = java.util.Arrays.copyOf(positions, capacity);
            expected = java.util.Arrays.copyOf(expected, capacity);
            targets = java.util.Arrays.copyOf(targets, capacity);
            index = new int[capacity * 2];
            int mask = index.length - 1;
            for (int slot = 0; slot < size; slot++) {
                int probe = mix(positions[slot]) & mask;
                while (index[probe] != 0) {
                    probe = (probe + 1) & mask;
                }
                index[probe] = slot + 1;
            }
        }

        private static int mix(int value) {
            int h = value * 0x9E3779B9;
            return (h ^ (h >>> 16)) & 0x7FFFFFFF;
        }

        /** Chunk-local x and z, and an absolute y, in twenty bits. */
        static int pack(int x, int y, int z) {
            return ((x & 15) << 16) | ((z & 15) << 12) | ((y + Y_BIAS) & 0xFFF);
        }

        static int localX(int packed) {
            return (packed >>> 16) & 15;
        }

        static int localZ(int packed) {
            return (packed >>> 12) & 15;
        }

        static int y(int packed) {
            return (packed & 0xFFF) - Y_BIAS;
        }
    }
}
