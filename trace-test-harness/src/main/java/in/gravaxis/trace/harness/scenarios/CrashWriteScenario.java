/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.bukkit.Bukkit;

/**
 * Writes an append-only sequence of forced records until something kills the JVM.
 *
 * <p>This is the target the crash rig shoots at in M1. It is not Trace's journal — that arrives in
 * M2 — but it has the property the journal will have to have: every record this scenario has
 * force-written must still be there, in order and undamaged, after a {@code kill -9}. Proving the
 * rig can detect that now means the real durability gate later is testing the journal rather than
 * testing the rig.
 *
 * <p>Writing happens on the async scheduler, never on a tick thread.
 */
public final class CrashWriteScenario implements Scenario {

    /** Fixed-width record: sequence number, CRC of it, newline. */
    static final int RECORD_BYTES = 22;

    static final String DATA_FILE = "crash-data.log";

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        int ratePerSecond = context.intParam("rate", 2000);
        int forceEvery = Math.max(1, context.intParam("forceEvery", 1));
        int maxRecords = context.intParam("maxRecords", 2_000_000);
        Path file = context.workingDirectory().resolve(DATA_FILE);
        context.result()
                .detail("crash.file", file.getFileName().toString())
                .detail("crash.ratePerSecond", ratePerSecond)
                .detail("crash.forceEvery", forceEvery);

        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
            long written = 0;
            try (FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(RECORD_BYTES);
                CRC32 crc = new CRC32();
                long nanosPerRecord = TimeUnit.SECONDS.toNanos(1) / Math.max(1, ratePerSecond);
                long startedAt = System.nanoTime();
                boolean announced = false;

                while (written < maxRecords) {
                    buffer.clear();
                    buffer.put(record(written, crc));
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    written++;
                    if (written % forceEvery == 0) {
                        channel.force(false);
                    }
                    if (!announced && written >= 100) {
                        announced = true;
                        // Only now is the scenario genuinely writing; this is what arms the kill.
                        context.announceReady("crash-write file=" + DATA_FILE);
                    }
                    long target = startedAt + written * nanosPerRecord;
                    long sleep = target - System.nanoTime();
                    if (sleep > 0) {
                        TimeUnit.NANOSECONDS.sleep(sleep);
                    }
                }
                context.result().detail("crash.writtenBeforeExit", written);
            } catch (IOException | InterruptedException e) {
                context.result().failure("Writer stopped early after " + written + " records: " + e);
            }
            done.complete(null);
        });
        return done;
    }

    static byte[] record(long sequence, CRC32 crc) {
        String body = String.format("%012d", sequence);
        crc.reset();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        String line = body + " " + String.format("%08x", crc.getValue()) + "\n";
        return line.getBytes(StandardCharsets.US_ASCII);
    }
}
