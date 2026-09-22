/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.testing;

import in.gravaxis.trace.core.capture.BoundedPayloadQueue;
import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.core.journal.CaptureEnvelope;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.ScanPlan;
import java.util.List;

/** Shared synthetic identity for the handoff and its child-process crash boundaries. */
public final class PayloadFixture {
    public static final long TIME = 1_800_000_000_000L;

    private PayloadFixture() {}

    public static CaptureEnvelope event() {
        var batch = Events.batchOf(List.of(new Events(1, 2, 64, 3, TIME, 1, 10, 11, 16, Cause.PLACING)));
        return new CaptureEnvelope(
                batch.position(0), batch.states(0), batch.actorTime(0), batch.metadata(0), 7, new byte[] {0, -1, 3});
    }

    public static CursorPosition cursor() {
        return new CursorPosition(Morton.key(0, 0), TIME, 1);
    }

    public static ScanPlan plan() {
        return ScanPlan.of(1, new BlockBox(0, 0, 0, 15, 100, 15), TIME, TIME + 1, ScanPlan.Order.NEWEST_FIRST);
    }

    public static BoundedPayloadQueue.Offer offer(BoundedPayloadQueue queue) {
        var e = event();
        return queue.offer(
                e.position(),
                e.states(),
                e.actorTime(),
                e.metadata(),
                e.version(),
                e.payload(),
                e.payload().length,
                TIME,
                TIME);
    }
}
