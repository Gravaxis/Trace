/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Halts only at announced publication boundaries so the parent proves an actual interrupted write. */
public final class DictionaryCrashWorker {
    private DictionaryCrashWorker() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        String kind = args[1], stop = args[2];
        var uuid = UUID.fromString("00000000-0000-4000-8000-000000000123");
        var writer = new DictionaryFile(phase -> {
            if (phase.equals(stop)) halt(directory, kind + ":" + phase);
        });
        if (kind.equals("actor")) {
            var dictionary = ActorDictionary.load(directory, writer);
            dictionary.register(uuid, "crash-fixture");
            if (dictionary.idOf(uuid) != 16 || ActorDictionary.load(directory).idOf(uuid) != 16)
                throw new AssertionError("Published actor is not durable");
        } else {
            var dictionary = WorldDictionary.load(directory, writer);
            dictionary.register(uuid);
            if (dictionary.idOf(uuid) != 0 || WorldDictionary.load(directory).idOf(uuid) != 0)
                throw new AssertionError("Published world is not durable");
        }
        halt(directory, kind + ":published");
    }

    private static void halt(Path directory, String phase) throws java.io.IOException {
        Files.writeString(directory.resolve("ready.tmp"), phase);
        Files.move(
                directory.resolve("ready.tmp"),
                directory.resolve("ready"),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        while (true) java.util.concurrent.locks.LockSupport.park();
    }
}
