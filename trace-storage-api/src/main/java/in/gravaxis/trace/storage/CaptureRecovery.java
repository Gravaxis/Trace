/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage;

import in.gravaxis.trace.core.capture.CaptureLoss;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps restart loss recovery independent of platform boot and generic crash-window detection. */
public final class CaptureRecovery {
    private CaptureRecovery() {}

    public static boolean recordLoss(Path lossFile, EventStore store) throws IOException, StoreException {
        if (!Files.exists(lossFile)) return false;
        try (CaptureLoss losses = CaptureLoss.open(lossFile)) {
            if (losses.toMillis() <= 0) return false;
            store.recordGap(new GapRecord(
                    losses.fromMillis(),
                    losses.toMillis(),
                    GapRecord.Reason.OVERFLOW,
                    losses.count(),
                    "recovered persistent capture-loss bounds"));
            return true;
        }
    }
}
