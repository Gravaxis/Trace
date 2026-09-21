/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Generated names and provenance make raw results traceable without hand-created report directories. */
final class MeasurementRun {
    private MeasurementRun() {}

    static Path create(Path root, String command) throws Exception {
        String commit = git("rev-parse", "HEAD");
        boolean dirty = git("status", "--porcelain")
                .lines()
                .anyMatch(line -> !line.isBlank() && !line.substring(3).startsWith("benchmarks/results/"));
        String stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Path directory = root.resolve(stamp + "-" + commit.substring(0, 12) + (dirty ? "-dirty" : ""));
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("environment.json"),
                "{\"commit\":" + StorageDensity.quote(commit)
                        + ",\"dirty\":" + dirty + ",\"command\":" + StorageDensity.quote(command)
                        + ",\"jvm\":" + StorageDensity.quote(System.getProperty("java.runtime.version"))
                        + ",\"os\":" + StorageDensity.quote(System.getProperty("os.name"))
                        + ",\"cpu\":"
                        + StorageDensity.quote(System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"))
                        + ",\"processors\":" + Runtime.getRuntime().availableProcessors()
                        + ",\"heapLimitBytes\":" + Runtime.getRuntime().maxMemory() + "}\n");
        return directory;
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Git provenance unavailable");
        return text.stripTrailing();
    }
}
