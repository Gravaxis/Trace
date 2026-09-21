/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.testing;

import in.gravaxis.trace.core.capture.BoundedPayloadQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Keeps the actual queue mapping live until the parent kills this process without close or force. */
public final class PayloadQueueCrashWorker {
    private PayloadQueueCrashWorker() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (var queue = BoundedPayloadQueue.open(root.resolve("queue"), 1, 8)) {
            byte[] bytes = {1, 2, -1};
            if (queue.offer(11, 22, 33, 44, 7, bytes, 3, 100, 110) != BoundedPayloadQueue.Offer.ACCEPTED)
                throw new IllegalStateException("Acceptance branch not exercised");
            if (queue.offer(55, 66, 77, 88, 7, bytes, 3, 90, 120) != BoundedPayloadQueue.Offer.FULL)
                throw new IllegalStateException("Overflow branch not exercised");
            Files.writeString(root.resolve("ready"), "accepted-and-rejected-without-close");
            new CountDownLatch(1).await();
        }
    }
}
