/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.pipeline.StoreConsumer;
import in.gravaxis.trace.storage.MaintenancePolicy;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.helpers.NOPLogger;

/** Synthetic load through real capture, journal and consumer; not a server throughput benchmark. */
public final class MaintenanceCosts {
    private MaintenanceCosts() {}

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        Files.createDirectories(scratch);
        Path output = MeasurementRun.create(Path.of(args[1]), ":benchmarks:maintenanceCosts");
        Path report = output.resolve("measurements.json");
        Files.writeString(report, "{\"measured\":false,\"reason\":\"run incomplete\"}\n");
        List<String> cases = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        for (int count : new int[] {10_000, 100_000, 500_000}) {
            System.out.println("Maintenance fixture input rows: " + count);
            cases.add(measure(Files.createTempDirectory(scratch, "run-"), count, 60_000));
            cases.add(measure(Files.createTempDirectory(scratch, "run-"), count, 50));
            phases.add(measurePhases(Files.createTempDirectory(scratch, "phases-"), count));
        }
        Files.writeString(
                report,
                "{\"measured\":true,\"seed\":20260920,\"inputShards\":8,\"producerBurst\":64,\"producerParkNanos\":1000000,"
                        + "\"samplingParkNanos\":2000000,\"maxInputRows\":1000000,"
                        + "\"scope\":\"Real CaptureService, StoreConsumer, journal, SQLite; synthetic states/actors; no Bukkit dispatch, world reads, dictionaries or blobs.\","
                        + "\"limits\":\"Sampled heap/backlog are observed maxima, not true peaks. Heap excludes native SQLite and mapped pages. Elapsed includes consumer drain and scheduling. No production latency or throughput claim.\","
                        + "\"cases\":[" + String.join(",", cases) + "],\"isolatedPhases\":[" + String.join(",", phases)
                        + "]}\n");
        System.out.println("Generated maintenance report: " + output);
    }

    private static String measurePhases(Path directory, int count) throws Exception {
        try (var store = SqliteEventStore.open(directory)) {
            var batch = new RecordBatch(1000);
            long lsn = 0;
            for (int i = 0; i < count; i++) {
                batch.add(
                        EventRecords.packPosition(i % 1024, 64, i / 1024),
                        EventRecords.packStates(1, 2, 0),
                        EventRecords.packActorTime(86_400_000L + i, 16),
                        EventRecords.packMetadata(i & 65535, 1, 1, 1, 16, 0));
                if (batch.isFull()) {
                    store.append(batch, lsn++);
                    batch.clear();
                }
            }
            if (batch.count() > 0) store.append(batch, lsn);
            List<String> results = new ArrayList<>();
            var budget = new in.gravaxis.trace.storage.MaintenanceBudget(1024, 2, 50, () -> false);
            for (var operation : new in.gravaxis.trace.storage.MaintenanceOperation[] {
                in.gravaxis.trace.storage.MaintenanceOperation.SEAL,
                in.gravaxis.trace.storage.MaintenanceOperation.VERIFY,
                in.gravaxis.trace.storage.MaintenanceOperation.CHECKPOINT
            }) {
                long total = 0, maximum = 0, progressed = 0, completed = 0, steps = 0;
                boolean done = false;
                while (!done && steps < count * 4L) {
                    long start = System.nanoTime();
                    var result = store.maintain(operation, budget, 0);
                    long elapsed = System.nanoTime() - start;
                    total += elapsed;
                    maximum = Math.max(maximum, elapsed);
                    steps++;
                    if (result.state() == in.gravaxis.trace.storage.MaintenanceResult.State.PROGRESSED) progressed++;
                    else if (result.state() == in.gravaxis.trace.storage.MaintenanceResult.State.COMPLETED) completed++;
                    else if (result.state() == in.gravaxis.trace.storage.MaintenanceResult.State.NO_WORK) done = true;
                    else throw new IllegalStateException("Unexpected phase outcome " + result.state());
                    if (operation == in.gravaxis.trace.storage.MaintenanceOperation.CHECKPOINT) done = true;
                }
                if (!done
                        || completed == 0
                        || (operation != in.gravaxis.trace.storage.MaintenanceOperation.CHECKPOINT && progressed == 0))
                    throw new IllegalStateException("Phase branch not exercised " + operation);
                results.add("{\"operation\":\"" + operation + "\",\"steps\":" + steps + ",\"progressed\":" + progressed
                        + ",\"completed\":" + completed + ",\"totalNanos\":" + total + ",\"maxStepNanos\":" + maximum
                        + "}");
            }
            if (store.stats().hotRows() != 0
                    || store.stats().sealedRows() != count
                    || store.verify().verified() != store.stats().shardCount())
                throw new IllegalStateException("Phase output invalid");
            return "{\"inputRows\":" + count + ",\"rowBudget\":1024,\"millisBudget\":50,\"phases\":["
                    + String.join(",", results) + "]}";
        }
    }

    static String measure(Path work, int count, long budgetMillis) throws Exception {
        try (var store = SqliteEventStore.open(work.resolve("store"));
                var capture = new CaptureService(work.resolve("rings"), 8192)) {
            seed(store, count);
            try (var journal =
                    JournalWriter.open(work.resolve("journal"), 64L * 1024 * 1024, 20260920, store.appliedLsn())) {
                var consumer = new StoreConsumer(
                        capture,
                        journal,
                        store,
                        NOPLogger.NOP_LOGGER,
                        200,
                        Long.MAX_VALUE,
                        new MaintenancePolicy(true, 100, 1_000_000, 16, budgetMillis, 0));
                AtomicBoolean running = new AtomicBoolean(true);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread writer = new Thread(consumer, "measurement-consumer");
                Thread producer = new Thread(
                        () -> {
                            try {
                                for (int i = 0; i < 64; i++) capture.captureBlockChange(1, i, 64, 0, 1, 16, 1, 1);
                                capture.confirmStaged((w, x, y, z) -> 2);
                                while (running.get() && !consumer.maintenanceInProgress())
                                    LockSupport.parkNanos(100_000);
                                while (running.get()) {
                                    for (int i = 0; i < 64; i++) capture.captureBlockChange(1, i, 64, 0, 1, 16, 1, 1);
                                    capture.confirmStaged((w, x, y, z) -> 2);
                                    LockSupport.parkNanos(1_000_000);
                                }
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        },
                        "measurement-capture");
                long start = System.nanoTime();
                List<String> samples = new ArrayList<>();
                writer.start();
                producer.start();
                long elapsed;
                long firstActivePublished = -1, lastActivePublished = -1;
                try {
                    while (consumer.maintenanceCompleted() == 0 && System.nanoTime() - start < 90_000_000_000L) {
                        boolean active = consumer.maintenanceInProgress();
                        long seen = capture.published();
                        if (active) {
                            if (firstActivePublished < 0) firstActivePublished = seen;
                            lastActivePublished = seen;
                        }
                        samples.add("[" + (System.nanoTime() - start) + "," + consumer.pendingRecords() + ","
                                + ManagementFactory.getMemoryMXBean()
                                        .getHeapMemoryUsage()
                                        .getUsed() + "," + active + "," + seen + "]");
                        LockSupport.parkNanos(2_000_000);
                    }
                    elapsed = System.nanoTime() - start;
                    if (consumer.maintenanceCompleted() == 0)
                        throw new IllegalStateException(
                                "No scheduled compaction attempted: " + consumer.maintenanceStatus());
                    if (budgetMillis == 60_000 && consumer.maintenanceCompleted() == 0)
                        throw new IllegalStateException("Completion fixture did not complete");
                } finally {
                    running.set(false);
                    producer.join();
                    consumer.stop();
                    writer.join(90_000);
                    if (writer.isAlive()) throw new IllegalStateException("Consumer did not stop");
                }
                if (failure.get() != null) throw new IllegalStateException("Producer failed", failure.get());
                long published = capture.published();
                long stored = consumer.recordsStored();
                if (published == 0 || stored == 0 || consumer.storeFailures() != 0)
                    throw new IllegalStateException("Capture/store branch was not healthy");
                if (firstActivePublished < 0 || lastActivePublished <= firstActivePublished)
                    throw new IllegalStateException("No observed capture publication during maintenance");
                long expectedShards = store.stats().shardCount();
                if (consumer.maintenanceCompleted() == 0
                        || store.stats().sealedRows() < count
                        || store.stats().sealedRows() + store.stats().hotRows() != count + stored)
                    throw new IllegalStateException("Scheduled work did not preserve seeded and captured rows");
                if (store.verify().verified() != expectedShards)
                    throw new IllegalStateException("Output did not verify");
                return "{\"inputRows\":" + count + ",\"elapsedNanos\":" + elapsed
                        + ",\"budgetMillis\":" + budgetMillis
                        + ",\"maintenanceElapsedNanos\":" + consumer.maintenanceElapsedNanos()
                        + ",\"maintenance\":" + StorageDensity.quote(consumer.maintenanceStatus())
                        + ",\"published\":" + published + ",\"stored\":" + stored
                        + ",\"pendingAtStop\":" + consumer.pendingRecords() + ",\"dropped\":" + capture.dropped()
                        + ",\"lossCount\":" + capture.lossCount() + ",\"samplesNanosPendingHeapActivePublished\":["
                        + String.join(",", samples) + "]}";
            }
        }
    }

    private static void seed(SqliteEventStore store, int count) throws Exception {
        SplittableRandom random = new SplittableRandom(20260920);
        RecordBatch batch = new RecordBatch(1000);
        long lsn = 0;
        for (int i = 0; i < count; i++) {
            batch.add(
                    EventRecords.packPosition(random.nextInt(-2048, 2048), 64, random.nextInt(-2048, 2048)),
                    EventRecords.packStates(1, 2, 0),
                    EventRecords.packActorTime(86_400_000L + i, 16),
                    EventRecords.packMetadata(i & 65535, 1, 1, 1, 16, 0));
            if (batch.isFull() || (i + 1) % (count / 8) == 0) {
                store.append(batch, lsn++);
                batch.clear();
            }
            if ((i + 1) % (count / 8) == 0) store.seal();
        }
        if (store.stats().shardCount() != 8 || store.stats().sealedRows() != count)
            throw new IllegalStateException("Fixture did not seal expected input");
    }
}
