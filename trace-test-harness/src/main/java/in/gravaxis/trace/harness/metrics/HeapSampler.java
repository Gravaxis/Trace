/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.metrics;

import com.sun.management.GarbageCollectionNotificationInfo;
import in.gravaxis.trace.harness.HarnessResult;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Tracks the highest heap occupancy observed <em>after</em> a garbage collection.
 *
 * <p>Peak used heap on its own says little: it mostly measures how lazily the collector ran. Heap
 * still live after a collection is the number that tells you whether something is retaining, which
 * is what the memory-flatness gate (P2) is about — a rollback of fifty million edits must not
 * retain more than one of twenty-seven thousand.
 */
public final class HeapSampler implements NotificationListener {

    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private volatile long peakAfterGcBytes;
    private final AtomicInteger collections = new AtomicInteger();

    public void start() {
        peakAfterGcBytes = 0;
        collections.set(0);
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this, null, null);
                emitters.add(emitter);
            }
        }
    }

    public void stop() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(this);
            } catch (Exception ignored) {
                // Listener already gone; nothing to do.
            }
        }
        emitters.clear();
    }

    @Override
    public void handleNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        long used = 0;
        for (Map.Entry<String, MemoryUsage> entry :
                info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
            if (isHeapPool(entry.getKey())) {
                used += entry.getValue().getUsed();
            }
        }
        collections.incrementAndGet();
        if (used > peakAfterGcBytes) {
            peakAfterGcBytes = used;
        }
    }

    private static boolean isHeapPool(String name) {
        // Names vary by collector; the non-heap pools are the ones to exclude.
        return !name.contains("Metaspace") && !name.contains("Code") && !name.contains("Compressed Class");
    }

    /** Adds heap measurements to the result under {@code <prefix>.*}. */
    public void report(HarnessResult result, String prefix) {
        int gcCount = collections.get();
        result.metric(prefix + ".collections", gcCount, "count");
        if (gcCount > 0) {
            result.metric(prefix + ".peakAfterGc", (double) peakAfterGcBytes, "bytes");
        } else {
            // Reporting "0 bytes retained" when nothing was collected would read as a result. It is
            // the absence of one.
            result.detail(prefix + ".peakAfterGc", "no collection occurred during the scenario");
        }
    }
}
