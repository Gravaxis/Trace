/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import in.gravaxis.trace.core.journal.CaptureEnvelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Startup-owned fixed pool avoids allocating or opening mappings on region threads (ADR-0012). */
public final class PayloadQueues implements AutoCloseable {
    public static final int CAPACITY = 16;
    private final List<BoundedPayloadQueue> queues;

    private PayloadQueues(List<BoundedPayloadQueue> queues) {
        this.queues = List.copyOf(queues);
    }

    /** Blocking startup only. Slots require exclusive producer ownership; no reclamation or spill. */
    public static PayloadQueues open(Path directory) throws IOException {
        List<BoundedPayloadQueue> opened = new ArrayList<>();
        try {
            for (int slot = 0; slot < CaptureService.MAX_SLOTS; slot++)
                opened.add(BoundedPayloadQueue.open(
                        directory.resolve("p-" + slot + ".queue"), CAPACITY, CaptureEnvelope.MAX_PAYLOAD));
            return new PayloadQueues(opened);
        } catch (IOException | RuntimeException failure) {
            for (var queue : opened)
                try {
                    queue.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
            throw failure;
        }
    }

    public List<BoundedPayloadQueue> queues() {
        return queues;
    }

    /** Blocking shutdown after all producers and the consumer stop. */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (var queue : queues) {
            try {
                queue.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
