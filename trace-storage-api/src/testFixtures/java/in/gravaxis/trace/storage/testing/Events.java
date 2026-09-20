/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.testing;

import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.record.RecordKind;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.storage.RecordBatch;

/**
 * Builds events for tests, and holds them in a plain form the oracle can reason about.
 *
 * @param worldId the world
 * @param x block position
 * @param y block position
 * @param z block position
 * @param timestamp epoch milliseconds
 * @param sequence intra-millisecond sequence
 * @param beforeState interned state before the change
 * @param afterState interned state after the change
 * @param actorId who did it
 * @param cause why
 */
public record Events(
        int worldId,
        int x,
        int y,
        int z,
        long timestamp,
        int sequence,
        int beforeState,
        int afterState,
        int actorId,
        Cause cause) {

    /** Packs this event into a batch, the way the capture path would. */
    public void addTo(RecordBatch batch) {
        batch.add(
                EventRecords.packPosition(x, y, z),
                EventRecords.packStates(beforeState, afterState, 0),
                EventRecords.packActorTime(TraceEpoch.toRelative(timestamp), actorId),
                EventRecords.packMetadata(sequence, worldId, cause.id(), RecordKind.BLOCK.id(), actorId, 0));
    }

    /** A batch holding exactly these events. */
    public static RecordBatch batchOf(java.util.List<Events> events) {
        RecordBatch batch = new RecordBatch(Math.max(1, events.size()));
        events.forEach(event -> event.addTo(batch));
        return batch;
    }
}
