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
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.OperationProgress;
import in.gravaxis.trace.storage.OperationState;
import in.gravaxis.trace.storage.RollbackOperation;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>Every rollback is a durable operation. It is recorded before the first block is touched, it
 * checkpoints the position it has reached at each chunk boundary, and it is marked finished only
 * when it really is. A run cut off by a crash therefore leaves a row that says what it was doing and
 * how far it got, and {@link #resume(long)} continues from exactly there — reading only the
 * remainder, because the stored position is a seek into the clustered key, not a count of rows to
 * skip.
 *
 * <p>Resuming is safe to do twice. The apply step verifies every block before it writes, so a
 * position already restored is counted as already-there and left alone. What makes that true across
 * runs is that the operation's time window is stored rather than recomputed: a rollback records its
 * own writes, and a resumed run working out a fresh window would take those in and start undoing
 * itself.
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

    /** This process, stamped on the operations it starts so an operator can tell runs apart. */
    private final String runId;

    /** Operations this process is running right now, so a resume cannot race one. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    /** Operations someone has asked to stop. Per operation, never a single shared flag. */
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();

    public RollbackService(
            Plugin plugin,
            EventStore store,
            StoreConsumer consumer,
            CaptureService capture,
            WorldDictionary worlds,
            BlockStateDictionary states,
            String runId) {
        this.plugin = plugin;
        this.store = store;
        this.consumer = consumer;
        this.capture = capture;
        this.worlds = worlds;
        this.states = states;
        this.runId = runId;
    }

    /** Asks an operation to stop at its next chunk boundary. */
    public void cancel(long operationId) {
        cancelled.add(operationId);
    }

    /** Operations that may still have work left, for the startup report and for {@code /trace}. */
    public List<RollbackOperation> unfinished() throws StoreException {
        return store.unfinishedOperations();
    }

    /**
     * Rolls back every recorded change in a box and time window.
     *
     * <p>Blocking, and must not be called from a tick thread: it waits for storage and for region
     * tasks. The caller runs it on the async scheduler.
     */
    public RollbackSummary rollback(World world, BlockBox box, long fromMillis, long toMillis, int actorId)
            throws StoreException {
        int worldId = worlds.idOf(world);
        if (worldId < 0) {
            return RollbackSummary.refused("Trace has no history for the world " + world.getName());
        }

        // Rows must stop moving before they are planned over: sealing first is also what lets an
        // interrupted run resume against the same rows it was reading.
        consumer.flushAndSeal(FLUSH_TIMEOUT_MILLIS);

        RollbackSummary refusal = refusalForGaps(fromMillis, toMillis);
        if (refusal != null) {
            return refusal;
        }

        RollbackOperation operation = RollbackOperation.starting(
                runId, worldId, box, fromMillis, toMillis, actorId, System.currentTimeMillis());
        long id = store.beginOperation(operation);
        return run(world, operation.withId(id), OperationProgress.NOTHING);
    }

    /**
     * Continues an operation a previous run did not finish.
     *
     * <p>Refuses rather than guesses in every case where continuing could be wrong: an operation
     * that is already finished, one this process is running right now, or one whose world is no
     * longer loaded. An operation left behind by an earlier run needs no such check, because one
     * server per data directory means the run that started it is gone.
     */
    public RollbackSummary resume(long operationId) throws StoreException {
        RollbackOperation operation = store.operation(operationId);
        if (operation == null) {
            return RollbackSummary.refused("There is no rollback with id " + operationId);
        }
        if (!operation.state().isResumable()) {
            return RollbackSummary.refused("Rollback " + operationId + " already finished (" + operation.state() + ")");
        }
        if (running.contains(operationId)) {
            return RollbackSummary.refused("Rollback " + operationId + " is running right now");
        }
        World world = worlds.worldOf(operation.worldId());
        if (world == null) {
            return RollbackSummary.refused(
                    "Rollback " + operationId + " is for a world this server does not have loaded");
        }

        consumer.flushAndSeal(FLUSH_TIMEOUT_MILLIS);
        RollbackSummary refusal = refusalForGaps(operation.fromMillis(), operation.toMillis());
        if (refusal != null) {
            return refusal;
        }
        return run(world, operation, operation.progress());
    }

    private @Nullable RollbackSummary refusalForGaps(long fromMillis, long toMillis) throws StoreException {
        List<GapRecord> gaps = store.gapsBetween(fromMillis, toMillis);
        if (gaps.isEmpty()) {
            return null;
        }
        GapRecord first = gaps.get(0);
        return RollbackSummary.refused(
                "Events were lost between " + first.fromMillis() + " and " + first.toMillis() + " ("
                        + first.reason() + "). Rolling back across a gap would produce a world that never"
                        + " existed, so Trace will not do it.");
    }

    /**
     * Streams the operation's remaining rows and applies them, checkpointing at chunk boundaries.
     *
     * <p>The cursor advances only over chunks that were fully applied. As soon as one chunk cannot
     * be applied — a region too busy to accept the task in time — the cursor stops advancing for the
     * rest of the run, even though later chunks are still attempted. Letting it advance past a
     * contended chunk would fence those rows out of every future resume, and the positions in them
     * would never be restored and never be reported.
     */
    private RollbackSummary run(World world, RollbackOperation operation, OperationProgress carried)
            throws StoreException {
        long id = operation.id();
        if (!running.add(id)) {
            return RollbackSummary.refused("Rollback " + id + " is already running");
        }
        cancelled.remove(id);

        ScanPlan plan = ScanPlan.of(
                        operation.worldId(),
                        operation.box(),
                        operation.fromMillis(),
                        operation.toMillis(),
                        ScanPlan.Order.NEWEST_FIRST)
                .withBatchSize(BATCH_SIZE)
                .resumeAfter(operation.cursor());

        ChunkFold fold = new ChunkFold();
        RollbackSummary.Builder summary = RollbackSummary.builder();
        MutationBatch batch = new MutationBatch(BATCH_SIZE);
        OperationState finalState = OperationState.DONE;

        try (MutationCursor cursor = store.scan(plan)) {
            long currentChunk = Long.MIN_VALUE;
            boolean any = false;
            boolean stopped = false;
            CursorPosition lastRow = null;
            CursorPosition chunkEnd = null;

            while (!stopped && cursor.next(batch)) {
                for (int i = 0; i < batch.size(); i++) {
                    long chunkKey = batch.chunkKey(i);
                    if (any && chunkKey != currentChunk) {
                        applyChunk(world, currentChunk, fold, summary);
                        fold.clear();
                        chunkEnd = lastRow;
                        checkpoint(id, chunkEnd, carried, summary);
                        if (cancelled.contains(id)) {
                            finalState = OperationState.CANCELLED;
                            stopped = true;
                            break;
                        }
                    }
                    summary.scanned();
                    currentChunk = chunkKey;
                    any = true;
                    lastRow = new CursorPosition(chunkKey, batch.timestamp(i), batch.sequence(i));
                    fold.accept(batch.x(i), batch.y(i), batch.z(i), batch.afterState(i), batch.beforeState(i));
                }
            }
            if (any && !stopped) {
                applyChunk(world, currentChunk, fold, summary);
                chunkEnd = lastRow;
                checkpoint(id, chunkEnd, carried, summary);
            }
            if (finalState == OperationState.DONE && summary.contendedCount() > 0) {
                finalState = OperationState.PARTIAL;
            }
        } catch (StoreException | RuntimeException e) {
            store.finishOperation(id, OperationState.FAILED, carried.plus(summary.progress()));
            running.remove(id);
            throw e;
        }

        store.finishOperation(id, finalState, carried.plus(summary.progress()));
        running.remove(id);
        return summary.build(id, finalState, carried);
    }

    /**
     * Durably records how far this run has got.
     *
     * <p>A null position means "counters only": the run has work it cannot promise is finished, so
     * the stored position stays where it was and a later resume redoes from there.
     */
    private void checkpoint(
            long id, @Nullable CursorPosition position, OperationProgress carried, RollbackSummary.Builder summary)
            throws StoreException {
        CursorPosition safe = summary.contendedCount() > 0 ? null : position;
        store.checkpointOperation(id, safe, carried.plus(summary.progress()));
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
        } catch (TimeoutException e) {
            // The region never ran the task. That is a chunk left undone, not a broken rollback:
            // it is counted, the operation ends PARTIAL rather than DONE, and the checkpoint stops
            // advancing so a resume comes back for it.
            summary.contended();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted applying chunk " + chunkX + "," + chunkZ, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Applying chunk " + chunkX + "," + chunkZ + " failed", e.getCause());
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
