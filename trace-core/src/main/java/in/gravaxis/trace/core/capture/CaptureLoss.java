/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Conservative process-surviving loss bounds. No tick-thread I/O or lock on record(). */
public final class CaptureLoss implements AutoCloseable {
    private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle().withInvokeExactBehavior();
    private final Arena arena;
    private final MemorySegment memory;

    private CaptureLoss(Arena arena, MemorySegment memory) {
        this.arena = arena;
        this.memory = memory;
    }

    public static CaptureLoss open(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Arena arena = Arena.ofShared();
        try (var channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MemorySegment memory = channel.map(FileChannel.MapMode.READ_WRITE, 0, 64, arena);
            CaptureLoss result = new CaptureLoss(arena, memory);
            if (result.fromMillis() == 0) LONG.setVolatile(memory, 0L, System.currentTimeMillis());
            return result;
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    public void record(long latestPossibleMillis) {
        long old;
        do {
            old = toMillis();
        } while (old < latestPossibleMillis && !(boolean) LONG.compareAndSet(memory, 8L, old, latestPossibleMillis));
        // Bounds are visible before count. Recovery checks the bound even if killed before this.
        long ignored = (long) LONG.getAndAdd(memory, 16L, 1L);
    }

    public long fromMillis() {
        return (long) LONG.getVolatile(memory, 0L);
    }

    public long toMillis() {
        return (long) LONG.getVolatile(memory, 8L);
    }

    public long count() {
        return (long) LONG.getVolatile(memory, 16L);
    }

    @Override
    public void close() {
        memory.force();
        arena.close();
    }
}
