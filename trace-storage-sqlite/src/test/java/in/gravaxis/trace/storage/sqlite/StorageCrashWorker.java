/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.geom.Morton;
import in.gravaxis.trace.storage.CursorPosition;
import in.gravaxis.trace.storage.testing.Events;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class StorageCrashWorker {
    private StorageCrashWorker() {}

    static CursorPosition blobPosition() {
        return new CursorPosition(Morton.key(0, 0), ShardMaintenanceTest.T, 1);
    }

    private static void ready(Path directory, String phase) {
        try {
            Files.writeString(directory.resolve("ready"), phase);
            while (true) Thread.sleep(1000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (args[1].equals("captured")) {
            var event = in.gravaxis.trace.storage.testing.PayloadFixture.event();
            var queue =
                    in.gravaxis.trace.core.capture.BoundedPayloadQueue.open(directory.resolve("payload.queue"), 1, 8);
            if (in.gravaxis.trace.storage.testing.PayloadFixture.offer(queue)
                    != in.gravaxis.trace.core.capture.BoundedPayloadQueue.Offer.ACCEPTED)
                throw new AssertionError("Accepted branch absent");
            if (queue.offer(0, 0, 0, 0, 0, new byte[0], 0, 100, 200)
                    != in.gravaxis.trace.core.capture.BoundedPayloadQueue.Offer.FULL)
                throw new AssertionError("Full branch absent");
            if (queue.offer(0, 0, 0, 0, 0, new byte[9], 9, 90, 210)
                    != in.gravaxis.trace.core.capture.BoundedPayloadQueue.Offer.OVERSIZED)
                throw new AssertionError("Oversized branch absent");
            var store = SqliteEventStore.open(directory.resolve("store"));
            var journal = in.gravaxis.trace.core.journal.JournalWriter.open(directory.resolve("journal"), 4096, 1);
            if (args[2].equals("capture.queued")) ready(directory, args[2]);
            if (args[2].equals("capture.journal")) {
                journal.appendCaptured(
                        event,
                        in.gravaxis.trace.storage.testing.PayloadFixture.TIME,
                        in.gravaxis.trace.storage.testing.PayloadFixture.TIME,
                        90);
                journal.force();
                ready(directory, args[2]);
            }
            store.captureProbe(phase -> {
                if (phase.equals(args[2])) ready(directory, phase);
            });
            int count = new in.gravaxis.trace.storage.PayloadHandoff(queue).drain(journal, store, Long.MAX_VALUE, 1);
            if (count != 1 || queue.pending() != 0) throw new AssertionError("Acknowledgement absent");
            if (args[2].equals("capture.acknowledged")) ready(directory, args[2]);
            throw new AssertionError("Capture crash branch not reached");
        }
        if (args[1].equals("migration")) {
            Path data = directory.resolve("store");
            Files.createDirectories(data);
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("manifest.db"))) {
                SqliteSchema.createManifest(c);
                try (var s = c.createStatement()) {
                    s.execute("INSERT INTO meta VALUES('format_version','2'),('applied_lsn','123')");
                }
            }
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("hot.db"))) {
                SqliteSchema.createHot(c, "main");
                try (var s = c.createStatement()) {
                    s.execute("INSERT INTO hot_event VALUES(1,0,117964800000000000,0,1,2,16,1,1,123)");
                }
            }
            try (var ignored = SqliteEventStore.open(data, phase -> {
                if (phase.equals(args[2])) ready(directory, phase);
            })) {
                throw new AssertionError("Migration branch not reached");
            }
        }
        if (args[1].equals("exhaustion")) {
            // Intentionally not closed: the parent kills the process while the marker is mapped.
            var capture = new CaptureService(directory.resolve("rings"), 4096);
            for (int i = 0; i <= CaptureService.MAX_SLOTS; i++) {
                final int x = i;
                Thread producer = new Thread(() -> {
                    capture.captureBlockChange(1, x, 64, 0, 1, 16, 1, 1);
                    capture.confirmStaged((w, bx, by, bz) -> 2);
                });
                if (i == CaptureService.MAX_SLOTS)
                    Files.writeString(directory.resolve("loss-start"), Long.toString(System.currentTimeMillis()));
                producer.start();
                producer.join();
            }
            if (capture.slotsExhausted() != 1
                    || capture.droppedNoSlot() != 1
                    || capture.published() != CaptureService.MAX_SLOTS
                    || capture.rings().size() != CaptureService.MAX_SLOTS)
                throw new AssertionError("Exhaustion branch not exercised exactly");
            ready(directory, "exhaustion.rejected");
        }
        try (var store = SqliteEventStore.open(directory.resolve("store"))) {
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(1, ShardMaintenanceTest.T, 16))), 0);
            store.seal();
            if (args[1].equals("blob")) {
                String id = store.putBlob(1, new byte[] {0, 1, -1});
                Files.writeString(directory.resolve("blob-id"), id);
                if (args[2].equals("blob.payload")) ready(directory, "blob.payload");
                store.attachBlob(1, blobPosition(), id);
                if (args[2].equals("blob.reference")) ready(directory, "blob.reference");
                throw new AssertionError("Requested blob crash branch was never reached");
            }
            store.append(Events.batchOf(List.of(ShardMaintenanceTest.event(2, ShardMaintenanceTest.T + 1, 17))), 1);
            store.seal();
            store.maintenanceProbe(phase -> {
                if (!phase.equals(args[2])) return;
                ready(directory, phase);
            });
            if (args[1].equals("incremental")) {
                for (int i = 0; i < 100; i++)
                    store.maintain(
                            in.gravaxis.trace.storage.MaintenanceOperation.COMPACT,
                            new in.gravaxis.trace.storage.MaintenanceBudget(1, 2, 5000, () -> false),
                            0);
            } else if (args[1].equals("compact")) store.compact();
            else store.purgeActor(16, ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100);
            throw new AssertionError("Requested crash branch was never reached");
        }
    }
}
