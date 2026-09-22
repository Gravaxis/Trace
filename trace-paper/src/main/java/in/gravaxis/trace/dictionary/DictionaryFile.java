/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import in.gravaxis.trace.storage.StoreException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Persist-before-publish ordering prevents a recoverable event from naming a volatile identity. */
final class DictionaryFile {
    static final DictionaryFile DEFAULT = new DictionaryFile(phase -> {});
    private final Probe probe;

    DictionaryFile(Probe probe) {
        this.probe = probe;
    }

    /** Blocking: startup or the storage worker only. No non-atomic fallback is safe here. */
    void replace(Path file, String contents) throws StoreException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (var channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(contents));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            probe.reached("forced");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            probe.reached("replaced");
        } catch (IOException failure) {
            throw new StoreException(StoreException.Reason.DISK, "Could not persist dictionary " + file, failure);
        }
    }

    @FunctionalInterface
    interface Probe {
        void reached(String phase) throws IOException;
    }
}
