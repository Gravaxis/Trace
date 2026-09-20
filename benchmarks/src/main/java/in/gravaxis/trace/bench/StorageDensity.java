/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Off-server SPIKE-2 runner. It never serializes source rows, identifiers or SQL exceptions. */
public final class StorageDensity {
    private static final long SEED = 20260921;

    private StorageDensity() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "{\"measured\":false,\"reason\":\"run did not complete\"}\n");
        try {
            List<String> measurements = new ArrayList<>();
            for (long span : new long[] {60_000, 3_600_000}) {
                Path work = Files.createTempDirectory(
                        Path.of(args[2]).toAbsolutePath().getParent(), "density-");
                System.out.println("Measuring synthetic Trace fixture");
                List<DensityReader.Report> reports = measureTrace(work, 100_000, span, 60_000);
                measurements.add("{\"kind\":\"trace-synthetic\",\"seed\":" + SEED
                        + ",\"events\":100000,\"spanMillis\":" + span + ",\"sealIntervalMillis\":60000,\"databases\":["
                        + String.join(
                                ",",
                                reports.stream().map(DensityReader.Report::json).toList()) + "]}");
            }
            if (args[0].isBlank()) {
                measurements.add("{\"kind\":\"private-input\",\"measured\":false,\"reason\":\"not supplied\"}");
            } else {
                System.out.println("Measuring private input through aggregate-only dbstat; this may take a long time");
                measurements.add(
                        DensityReader.measure(Path.of(args[0]), "private-input").json());
            }
            String report =
                    "{\"measured\":true,\"timestamp\":" + quote(Instant.now().toString())
                            + ",\"commit\":" + quote(git("rev-parse", "HEAD"))
                            + ",\"dirty\":" + !git("status", "--porcelain").isBlank()
                            + ",\"jvm\":" + quote(System.getProperty("java.runtime.version"))
                            + ",\"os\":" + quote(System.getProperty("os.name"))
                            + ",\"cpu\":" + quote(System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"))
                            + ",\"heapLimitBytes\":" + Runtime.getRuntime().maxMemory()
                            + ",\"driver\":\"org.xerial:sqlite-jdbc:3.49.1.0\","
                            + "\"counting\":\"dbstat allocated table/index pages, not payload bytes; freelist and unassigned pages separate; WAL/SHM separate. Trace includes manifest, hot and sealed shards. Synthetic numeric state/actor IDs only; dictionaries, journals, rings and blobs excluded. Private input includes all tables and indexes, with block attribution separate.\","
                            + "\"limitations\":\"Different data and workloads; no equivalence, production density, migration savings, throughput or headline ratio established. Private input rerunnable only by its holder. Physical filesystem allocation is not measured.\","
                            + "\"measurements\":[" + String.join(",", measurements) + "]}\n";
            Files.writeString(output, report);
            System.out.println("Aggregate density report complete");
        } catch (Exception e) {
            // Deliberately do not propagate source SQL, paths or values to Gradle logs.
            System.err.println("Density measurement failed; report remains not measured");
            System.exit(1);
        }
    }

    static List<DensityReader.Report> measureTrace(Path directory, int count, long spanMillis, long sealInterval)
            throws Exception {
        SplittableRandom random = new SplittableRandom(SEED);
        long base = TraceEpoch.EPOCH_MILLIS + 86_400_000;
        RecordBatch batch = new RecordBatch(1000);
        long window = 0;
        long lsn = 0;
        try (SqliteEventStore store = SqliteEventStore.open(directory)) {
            for (int i = 0; i < count; i++) {
                long offset = i * spanMillis / count;
                if (offset / sealInterval != window) {
                    store.append(batch, lsn++);
                    batch.clear();
                    store.seal();
                    window = offset / sealInterval;
                }
                int actor = 16 + random.nextInt(100);
                batch.add(
                        EventRecords.packPosition(
                                random.nextInt(-2048, 2048), random.nextInt(-64, 320), random.nextInt(-2048, 2048)),
                        EventRecords.packStates(1 + random.nextInt(256), 1 + random.nextInt(256), 0),
                        EventRecords.packActorTime(TraceEpoch.toRelative(base + offset), actor),
                        EventRecords.packMetadata(i & 0xFFFF, 1, 1, 1, actor, 0));
                if (batch.isFull()) {
                    store.append(batch, lsn++);
                    batch.clear();
                }
            }
            store.append(batch, lsn);
            store.seal();
            if (store.stats().sealedRows() != count) throw new IllegalStateException("Trace fixture count mismatch");
        }
        List<DensityReader.Report> reports = new ArrayList<>();
        try (var files = Files.walk(directory)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".db")).sorted().toList()) {
                reports.add(DensityReader.measure(file, "trace-db-" + reports.size()));
            }
        }
        return reports;
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Git provenance unavailable");
        return output.trim();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('\"').toString();
    }
}
