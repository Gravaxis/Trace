/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.storage.sqlite;

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
            if (args[1].equals("compact")) store.compact();
            else store.purgeActor(16, ShardMaintenanceTest.T, ShardMaintenanceTest.T + 100);
            throw new AssertionError("Requested crash branch was never reached");
        }
    }
}
