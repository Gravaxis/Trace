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
import in.gravaxis.trace.harness.TracePluginBridge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/**
 * The durability question, asked properly: was every lost event covered by a gap?
 *
 * <p>Runs after the rig restarts the server into the same directory. It is not enough that the
 * losses form a tidy suffix of the ground truth, and it is not enough to count them. For
 * <em>each</em> event the previous run wrote down and Trace does not have, there must be a recorded
 * gap whose window contains it. That is the property a rollback depends on: a gap is what makes it
 * refuse instead of quietly rebuilding a world that never existed.
 *
 * <p>A run that lost nothing proves nothing, so this scenario reports whether it was lossy and the
 * rig fails a set of iterations in which no iteration ever lost an event. Silence is not a pass.
 */
public final class CrashJournalVerifyScenario implements Scenario {

    /** How far either side of the recorded events to look for gaps. */
    private static final long WINDOW_MARGIN_MILLIS = 60_000;

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        String worldName = context.param("world", "world");
        Path truthFile = context.workingDirectory().resolve(CrashJournalWriteScenario.TRUTH_FILE);

        TracePluginBridge trace = TracePluginBridge.find();
        if (trace == null) {
            result.failure("Trace is not installed, so there is nothing to verify");
            return CompletableFuture.completedFuture(null);
        }
        if (!trace.has("storedPositions") || !trace.has("gapsBetween")) {
            result.failure("This Trace build has no storedPositions/gapsBetween seam to verify against");
            return CompletableFuture.completedFuture(null);
        }

        List<Truth> truth;
        try {
            truth = readTruth(truthFile);
        } catch (IOException e) {
            result.failure("Could not read the ground truth: " + e);
            return CompletableFuture.completedFuture(null);
        }
        if (truth.isEmpty()) {
            inconclusive(result, "the previous run recorded no events before it was killed");
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
            try {
                verify(context, trace, worldName, truth);
            } catch (Exception e) {
                result.failure("Verification failed: " + e);
            }
            done.complete(null);
        });
        return done;
    }

    private void verify(ScenarioContext context, TracePluginBridge trace, String worldName, List<Truth> truth)
            throws Exception {
        HarnessResult result = context.result();

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (Truth event : truth) {
            minX = Math.min(minX, event.x());
            minY = Math.min(minY, event.y());
            minZ = Math.min(minZ, event.z());
            maxX = Math.max(maxX, event.x());
            maxY = Math.max(maxY, event.y());
            maxZ = Math.max(maxZ, event.z());
            first = Math.min(first, event.millis());
            last = Math.max(last, event.millis());
        }

        String stored = trace.call(
                "storedPositions",
                worldName,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                first - WINDOW_MARGIN_MILLIS,
                last + WINDOW_MARGIN_MILLIS);
        if (stored.startsWith("refused") || stored.startsWith("failed")) {
            result.failure("Trace could not list what it stored: " + stored);
            return;
        }
        Set<String> present = new HashSet<>();
        for (String position : stored.split(";", -1)) {
            if (!position.isEmpty()) {
                present.add(position);
            }
        }

        String gapText = trace.call("gapsBetween", first - WINDOW_MARGIN_MILLIS, last + WINDOW_MARGIN_MILLIS);
        List<long[]> gaps = new ArrayList<>();
        for (String gap : gapText.split(";", -1)) {
            int colon = gap.indexOf(':');
            int dash = gap.indexOf('-');
            if (colon > 0 && dash > 0) {
                gaps.add(new long[] {
                    Long.parseLong(gap.substring(0, dash)), Long.parseLong(gap.substring(dash + 1, colon))
                });
            }
        }

        List<Truth> missing = new ArrayList<>();
        for (Truth event : truth) {
            if (!present.contains(event.x() + ":" + event.y() + ":" + event.z())) {
                missing.add(event);
            }
        }

        result.detail("crash.truthEvents", truth.size())
                .detail("crash.storedPositions", present.size())
                .detail("crash.missing", missing.size())
                .detail("crash.gaps", gapText.isEmpty() ? "none" : gapText)
                .metric("crash.truthEvents", truth.size(), "count")
                .metric("crash.missing", missing.size(), "count");

        List<Truth> uncovered = new ArrayList<>();
        for (Truth event : missing) {
            boolean covered = false;
            for (long[] gap : gaps) {
                if (event.millis() >= gap[0] && event.millis() <= gap[1]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(event);
            }
        }

        // The assertion the whole rig exists for.
        result.require(uncovered.isEmpty(), describeUncovered(uncovered, gaps, truth.size(), missing.size()));

        boolean lossy = !missing.isEmpty();
        result.detail("crash.lossy", lossy);
        if (!lossy) {
            inconclusive(
                    result,
                    "the kill landed between ticks: nothing was lost, so the gap-coverage property was never"
                            + " exercised");
        }
    }

    private static String describeUncovered(List<Truth> uncovered, List<long[]> gaps, int total, int missing) {
        if (uncovered.isEmpty()) {
            return "";
        }
        Truth worst = uncovered.getFirst();
        long earliestGap = gaps.stream().mapToLong(gap -> gap[0]).min().orElse(-1);
        String template = "%d of %d missing events (%d recorded in total) are in no gap. The first is index %d"
                + " at %d,%d,%d, captured at %d; the earliest gap starts at %d. A rollback over that window"
                + " would rebuild a world that never existed.";
        return template.formatted(
                uncovered.size(),
                missing,
                total,
                worst.index(),
                worst.x(),
                worst.y(),
                worst.z(),
                worst.millis(),
                earliestGap);
    }

    /**
     * Marks the run as having proved nothing.
     *
     * <p>Reported as a detail rather than a failure: one inconclusive iteration is expected now and
     * then, and it is the rig, across iterations, that decides a set with no lossy iteration at all
     * is not a pass.
     */
    private static void inconclusive(HarnessResult result, String why) {
        result.detail("crash.lossy", false).detail("crash.inconclusive", why);
    }

    private static List<Truth> readTruth(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(file);
        int size = CrashJournalWriteScenario.RECORD_BYTES;
        int complete = bytes.length / size;
        List<Truth> truth = new ArrayList<>(complete);
        for (int i = 0; i < complete; i++) {
            // A trailing partial record is normal: SIGKILL can interrupt a write. Only whole
            // records are ground truth.
            String line = new String(bytes, i * size, size, StandardCharsets.US_ASCII);
            String[] parts = line.trim().split(" +", -1);
            if (parts.length != 5) {
                break;
            }
            truth.add(new Truth(
                    Integer.parseInt(parts[0]),
                    Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4])));
        }
        return truth;
    }

    /** One event the previous run was about to make, as it wrote it down. */
    private record Truth(int index, long millis, int x, int y, int z) {}
}
