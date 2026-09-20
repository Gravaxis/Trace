/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.ScanPlan;
import in.gravaxis.trace.storage.StoreException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Merges every source a scan touches into one ordered stream.
 *
 * <p>A rollback folds the history of each position, so it needs rows in one global order — newest
 * first — even though they live in several files: one per sealed shard the window touches, plus the
 * hot window. Reading shard by shard would let an older row arrive after a newer one and corrupt
 * the fold, which is exactly the kind of defect that only shows up as "the rollback restored the
 * wrong block" months later.
 *
 * <p>Memory is one row per source plus the caller's batch, whatever the scan's size. That is the
 * memory-flatness guarantee, and it is why no part of this class keeps a list of rows.
 */
final class MergingCursor implements MutationCursor {

    private final ScanPlan plan;
    private final BlockBox box;
    private final boolean newestFirst;
    private final List<KeysetRowSource> sources;
    private final List<AutoCloseable> owned;

    private boolean started;
    private @Nullable CursorPosition position;

    MergingCursor(ScanPlan plan, List<KeysetRowSource> sources, List<AutoCloseable> owned) {
        this.plan = plan;
        this.box = plan.box();
        this.newestFirst = plan.order() == ScanPlan.Order.NEWEST_FIRST;
        this.sources = sources;
        this.owned = owned;
    }

    @Override
    public boolean next(MutationBatch batch) throws StoreException {
        batch.clear();
        try {
            if (!started) {
                for (KeysetRowSource source : sources) {
                    source.advance();
                }
                started = true;
            }
            while (!batch.isFull()) {
                KeysetRowSource best = pickNext();
                if (best == null) {
                    break;
                }
                emit(best, batch);
                best.advance();
            }
        } catch (SQLException e) {
            throw new StoreException(StoreException.Reason.INTERNAL, "Scan failed: " + e.getMessage(), e);
        }
        return batch.size() > 0;
    }

    private @Nullable KeysetRowSource pickNext() {
        KeysetRowSource best = null;
        for (KeysetRowSource source : sources) {
            if (!source.hasRow()) {
                continue;
            }
            if (best == null || beats(source, best)) {
                best = source;
            }
        }
        return best;
    }

    private boolean beats(KeysetRowSource candidate, KeysetRowSource incumbent) {
        int byChunk = Long.compareUnsigned(candidate.chunkKey(), incumbent.chunkKey());
        if (byChunk != 0) {
            return newestFirst ? byChunk > 0 : byChunk < 0;
        }
        int byTime = Long.compare(candidate.timestamp(), incumbent.timestamp());
        if (byTime != 0) {
            return newestFirst ? byTime > 0 : byTime < 0;
        }
        int bySequence = Integer.compare(candidate.sequence(), incumbent.sequence());
        return newestFirst ? bySequence > 0 : bySequence < 0;
    }

    private void emit(KeysetRowSource source, MutationBatch batch) {
        long chunkKey = source.chunkKey();
        int packed = source.localPosition();
        int x = (Morton.chunkX(chunkKey) << 4) + ShardKeys.localX(packed);
        int z = (Morton.chunkZ(chunkKey) << 4) + ShardKeys.localZ(packed);
        int y = ShardKeys.y(packed);

        position = new CursorPosition(chunkKey, source.timestamp(), source.sequence());

        // The Morton cover may be coarse and the box may cut a chunk in half, so every row is
        // checked against what the caller actually asked for. Correctness never rests on the cover.
        if (!box.contains(x, y, z) || !plan.acceptsActor(source.actorId())) {
            return;
        }
        batch.add(
                chunkKey,
                source.timestamp(),
                source.sequence(),
                x,
                y,
                z,
                source.beforeState(),
                source.afterState(),
                source.actorId(),
                source.cause(),
                source.kind());
    }

    @Override
    public @Nullable CursorPosition position() {
        return position;
    }

    @Override
    public void close() throws StoreException {
        List<Exception> failures = new ArrayList<>();
        for (KeysetRowSource source : sources) {
            try {
                source.close();
            } catch (Exception e) {
                failures.add(e);
            }
        }
        for (AutoCloseable closeable : owned) {
            try {
                closeable.close();
            } catch (Exception e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            StoreException exception =
                    new StoreException(StoreException.Reason.INTERNAL, "Closing the cursor failed", failures.get(0));
            failures.subList(1, failures.size()).forEach(exception::addSuppressed);
            throw exception;
        }
    }
}
