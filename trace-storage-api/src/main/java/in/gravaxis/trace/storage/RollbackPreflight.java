/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.record.RecordKind;

/** Refuses opaque or non-block history before any chunk can be applied (ADR-0017). */
public final class RollbackPreflight {
    private RollbackPreflight() {}

    public static void check(EventStore store, ScanPlan plan) throws StoreException {
        var batch = new MutationBatch(256);
        try (var cursor = store.scan(plan)) {
            while (cursor.next(batch)) {
                for (int i = 0; i < batch.size(); i++) {
                    if (batch.kind(i) != RecordKind.BLOCK.id()
                            || store.blobAt(
                                            plan.worldId(),
                                            new CursorPosition(
                                                    batch.chunkKey(i), batch.timestamp(i), batch.sequence(i)))
                                    != null)
                        throw new StoreException(
                                StoreException.Reason.CORRUPT,
                                "Rollback does not support this record kind or captured payload");
                }
            }
        }
    }
}
