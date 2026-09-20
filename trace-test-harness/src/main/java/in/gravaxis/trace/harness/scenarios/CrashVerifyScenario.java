/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.harness.HarnessResult;
import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * Checks what survived the kill: every forced record, in order, undamaged.
 *
 * <p>Runs after the rig restarts the server into the same directory. A trailing partial record is
 * tolerated and reported — a {@code write()} interrupted by SIGKILL can leave one — but a gap in the
 * sequence, a bad checksum or an empty file is a failure, because those are the shapes of real data
 * loss rather than of an interrupted append.
 */
public final class CrashVerifyScenario implements Scenario {

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        Path file = context.workingDirectory().resolve(CrashWriteScenario.DATA_FILE);
        if (!Files.isRegularFile(file)) {
            result.failure("No " + CrashWriteScenario.DATA_FILE + " survived the crash");
            return CompletableFuture.completedFuture(null);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            result.failure("Could not read " + file + ": " + e);
            return CompletableFuture.completedFuture(null);
        }

        int recordSize = CrashWriteScenario.RECORD_BYTES;
        int complete = bytes.length / recordSize;
        int tornTailBytes = bytes.length % recordSize;
        CRC32 crc = new CRC32();
        long expected = 0;
        int checked = 0;

        for (int i = 0; i < complete; i++) {
            String line = new String(bytes, i * recordSize, recordSize, StandardCharsets.US_ASCII);
            if (line.length() != recordSize || line.charAt(recordSize - 1) != '\n') {
                result.failure("Record " + i + " is not a well-formed line");
                break;
            }
            String body = line.substring(0, 12);
            String checksum = line.substring(13, 21);
            long sequence;
            try {
                sequence = Long.parseLong(body);
            } catch (NumberFormatException e) {
                result.failure("Record " + i + " has an unparseable sequence: '" + body + "'");
                break;
            }
            if (sequence != expected) {
                result.failure("Sequence gap: expected " + expected + " but found " + sequence
                        + " at record " + i + ". Forced records must never disappear from the middle.");
                break;
            }
            crc.reset();
            crc.update(body.getBytes(StandardCharsets.US_ASCII));
            if (!String.format("%08x", crc.getValue()).equals(checksum)) {
                result.failure("Record " + sequence + " is corrupt: checksum mismatch");
                break;
            }
            expected++;
            checked++;
        }

        result.detail("crash.fileBytes", bytes.length)
                .detail("crash.tornTailBytes", tornTailBytes)
                .metric("crash.recordsSurvived", (double) checked, "count")
                .metric("crash.bytesSurvived", (double) bytes.length, "bytes")
                .require(checked > 0, "The crash left no complete records at all")
                .require(checked == complete, "Verification stopped before the end of the file");
        if (tornTailBytes > 0) {
            // Expected: SIGKILL can interrupt a write() part-way. It is not loss of a forced record.
            result.detail("crash.note", "trailing partial record of " + tornTailBytes + " bytes, tolerated");
        }
        return CompletableFuture.completedFuture(null);
    }
}
