/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.runtime;

import in.gravaxis.trace.capture.BlockCaptureListener;
import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalFrames;
import in.gravaxis.trace.core.journal.JournalReader;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.ring.MappedEventRing;
import in.gravaxis.trace.core.time.TraceEpoch;
import in.gravaxis.trace.dictionary.ActorDictionary;
import in.gravaxis.trace.dictionary.BlockStateDictionary;
import in.gravaxis.trace.dictionary.WorldDictionary;
import in.gravaxis.trace.pipeline.StoreConsumer;
import in.gravaxis.trace.rollback.RollbackService;
import in.gravaxis.trace.storage.EventStore;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.RecordBatch;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.StoreStats;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Everything Trace owns while the server is up: the store, the journal, the rings, the consumer
 * thread, and the recovery that runs before any of them accept new work.
 *
 * <p>Startup order matters and is the whole crash story:
 *
 * <ol>
 *   <li>open the store, which reports the journal position it has already applied;
 *   <li>replay the journal from there — applying a batch twice is a no-op, so anything uncertain is
 *       simply re-applied;
 *   <li>replay whatever the rings still hold, because a mapped ring outlives the process that wrote
 *       it, and those records never reached the journal;
 *   <li>if the last run did not close cleanly, record a gap covering what could have been lost, so
 *       that a rollback over that window refuses instead of guessing.
 * </ol>
 */
public final class TraceRuntime implements AutoCloseable {

    private static final long JOURNAL_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final int RING_CAPACITY_RECORDS = 8192;
    private static final long FORCE_INTERVAL_MILLIS = 200;
    private static final long SEAL_INTERVAL_MILLIS = 60_000;

    /** How far past the last known capture a crash gap reaches. */
    private static final long CRASH_GAP_MARGIN_MILLIS = 1_000;

    private final Plugin plugin;
    private final Logger logger;
    private final Path directory;
    private final EventStore store;
    private final JournalWriter journal;
    private final CaptureService capture;
    private final StoreConsumer consumer;
    private final Thread consumerThread;
    private final WorldDictionary worlds;
    private final BlockStateDictionary states;
    private final ActorDictionary actors;
    private final RollbackService rollback;
    private final RecoveryReport recovery;

    private TraceRuntime(
            Plugin plugin,
            Logger logger,
            Path directory,
            EventStore store,
            JournalWriter journal,
            CaptureService capture,
            StoreConsumer consumer,
            Thread consumerThread,
            WorldDictionary worlds,
            BlockStateDictionary states,
            ActorDictionary actors,
            RollbackService rollback,
            RecoveryReport recovery) {
        this.plugin = plugin;
        this.logger = logger;
        this.directory = directory;
        this.store = store;
        this.journal = journal;
        this.capture = capture;
        this.consumer = consumer;
        this.consumerThread = consumerThread;
        this.worlds = worlds;
        this.states = states;
        this.actors = actors;
        this.rollback = rollback;
        this.recovery = recovery;
    }

    /** Opens everything, recovers from whatever the last run left behind, and starts capturing. */
    public static TraceRuntime start(Plugin plugin, Logger logger, Path directory) throws StoreException, IOException {
        Files.createDirectories(directory);
        Path journalDirectory = directory.resolve("journal");
        Path ringDirectory = directory.resolve("rings");
        Files.createDirectories(journalDirectory);
        Files.createDirectories(ringDirectory);

        WorldDictionary worlds = WorldDictionary.load(directory);
        BlockStateDictionary states = BlockStateDictionary.load(directory);
        ActorDictionary actors = ActorDictionary.load(directory);
        Bukkit.getWorlds().forEach(worlds::register);

        EventStore store = SqliteEventStore.open(directory.resolve("storage"));
        // Opened above whatever the store has already applied. Below it, every frame this run wrote
        // would be discarded as a replay of something older — which is how a previous build lost a
        // whole session's history while reporting it as written.
        long floor = store.appliedLsn();
        JournalWriter journal = JournalWriter.open(journalDirectory, JOURNAL_SEGMENT_BYTES, newSalt(), floor);
        if (journal.nextLsn() <= floor) {
            throw new IOException("The journal resumed at " + journal.nextLsn() + ", at or below the applied position "
                    + floor + "; refusing to start rather than discard everything this run captures");
        }
        RecoveryReport recovery = recover(logger, journalDirectory, ringDirectory, store, journal);
        CaptureService capture = new CaptureService(ringDirectory, RING_CAPACITY_RECORDS);
        StoreConsumer consumer =
                new StoreConsumer(capture, journal, store, logger, FORCE_INTERVAL_MILLIS, SEAL_INTERVAL_MILLIS);
        Thread consumerThread = new Thread(consumer, "trace-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        RollbackService rollback = new RollbackService(plugin, store, consumer, capture, worlds, states);
        TraceRuntime runtime = new TraceRuntime(
                plugin,
                logger,
                directory,
                store,
                journal,
                capture,
                consumer,
                consumerThread,
                worlds,
                states,
                actors,
                rollback,
                recovery);

        Bukkit.getPluginManager().registerEvents(new BlockCaptureListener(capture, worlds, states, actors), plugin);
        return runtime;
    }

    /**
     * Applies whatever the last run left in the journal and the rings.
     *
     * <p>Both replays are idempotent: the store discards a batch whose journal position it has
     * already applied, so recovery can afford to be pessimistic and re-apply anything uncertain.
     */
    private static RecoveryReport recover(
            Logger logger, Path journalDirectory, Path ringDirectory, EventStore store, JournalWriter journal)
            throws StoreException, IOException {
        long appliedLsn = store.appliedLsn();
        List<String> notes = new ArrayList<>();

        RecordBatch batch = new RecordBatch(4096);
        long[] replayed = {0};
        JournalReader.ReplaySummary summary =
                JournalReader.replay(journalDirectory, appliedLsn + 1, (lsn, type, words, count, min, max) -> {
                    if (type != JournalFrames.TYPE_EVENTS || count == 0) {
                        return;
                    }
                    batch.clear();
                    for (int i = 0; i < count; i++) {
                        int base = i * EventRecords.LONGS;
                        batch.add(words[base], words[base + 1], words[base + 2], words[base + 3]);
                    }
                    try {
                        store.append(batch, lsn);
                        replayed[0] += count;
                    } catch (StoreException e) {
                        throw new IOException("Replaying journal frame at " + lsn + " failed", e);
                    }
                });
        if (replayed[0] > 0) {
            notes.add(replayed[0] + " records replayed from the journal");
        }

        long fromRings = recoverRings(logger, ringDirectory, store, journal);
        if (fromRings > 0) {
            notes.add(fromRings + " records recovered from capture rings");
        }

        boolean unclean = !summary.cleanClose() && (summary.frames() > 0 || fromRings > 0 || appliedLsn > 0);
        if (unclean) {
            // A crash also costs whatever was staged in memory but never reached a ring — at most
            // the tick that was in flight. The gap says so, so a rollback over that moment refuses
            // rather than quietly working from history with a hole in it.
            long lastKnown = Math.max(summary.lastCaptureMillis(), newestFileTime(journalDirectory, ringDirectory));
            long from = summary.lastCaptureMillis() > 0 ? summary.lastCaptureMillis() : lastKnown;
            long to = lastKnown + CRASH_GAP_MARGIN_MILLIS;
            if (from > 0 && to >= from) {
                store.recordGap(new GapRecord(
                        from,
                        to,
                        GapRecord.Reason.CRASH_WINDOW,
                        -1,
                        "the server did not shut down cleanly; events captured in the last tick may be missing"));
                notes.add("recorded a crash gap covering " + from + ".." + to);
            }
        }
        return new RecoveryReport(replayed[0], fromRings, unclean, notes);
    }

    /**
     * Re-journals whatever the rings still hold from the last run, then applies it.
     *
     * <p>These records never reached the journal, so they are written to it now rather than being
     * given an invented position: a real frame is what makes them survive a crash during recovery
     * itself.
     */
    private static long recoverRings(Logger logger, Path ringDirectory, EventStore store, JournalWriter journal)
            throws StoreException, IOException {
        long recovered = 0;
        List<Path> ringFiles = new ArrayList<>();
        try (var files = Files.list(ringDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".ring"))
                    .forEach(ringFiles::add);
        }
        for (Path file : ringFiles) {
            int slot = slotOf(file);
            try (MappedEventRing ring = MappedEventRing.open(file, slot, RING_CAPACITY_RECORDS)) {
                int capacity = Math.max(1, (int) Math.min(ring.pending(), 4096));
                RecordBatch batch = new RecordBatch(capacity);
                long[] words = new long[capacity * EventRecords.LONGS];
                int[] written = {0};
                long[] captureBounds = {Long.MAX_VALUE, Long.MIN_VALUE};
                ring.replayPending((position, states, actorTime, metadata) -> {
                    if (batch.isFull()) {
                        return;
                    }
                    batch.add(position, states, actorTime, metadata);
                    int base = written[0] * EventRecords.LONGS;
                    words[base] = position;
                    words[base + 1] = states;
                    words[base + 2] = actorTime;
                    words[base + 3] = metadata;
                    long capturedAt = TraceEpoch.toAbsolute(EventRecords.relativeMillis(actorTime));
                    captureBounds[0] = Math.min(captureBounds[0], capturedAt);
                    captureBounds[1] = Math.max(captureBounds[1], capturedAt);
                    written[0]++;
                });
                if (written[0] > 0) {
                    long lsn = journal.appendEvents(
                            words, written[0], captureBounds[0], captureBounds[1], captureBounds[0]);
                    journal.force();
                    store.append(batch, lsn);
                    recovered += batch.count();
                    logger.info("Recovered {} records that ring {} still held after the last run", batch.count(), slot);
                }
                ring.reset();
            }
        }
        return recovered;
    }

    private static int slotOf(Path ringFile) {
        String name = ringFile.getFileName().toString();
        try {
            return Integer.parseInt(name.substring(2, name.indexOf('.')));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long newestFileTime(Path... directories) {
        long newest = 0;
        for (Path directory : directories) {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                }
            } catch (IOException e) {
                // Best effort: the gap bound falls back to the last journalled capture.
            }
        }
        return newest;
    }

    private static long newSalt() {
        return new SecureRandom().nextLong();
    }

    public CaptureService capture() {
        return capture;
    }

    public RollbackService rollback() {
        return rollback;
    }

    public WorldDictionary worlds() {
        return worlds;
    }

    public BlockStateDictionary states() {
        return states;
    }

    public ActorDictionary actors() {
        return actors;
    }

    public EventStore store() {
        return store;
    }

    public StoreConsumer consumer() {
        return consumer;
    }

    /** Registers a world that loaded after startup. */
    public void registerWorld(World world) {
        worlds.register(world);
    }

    /**
     * What an operator needs at three in the morning, in the order they need it.
     *
     * <p>Honest about what this build does not do yet, because an operator who assumes otherwise
     * finds out at the worst possible time.
     */
    public List<String> status() throws StoreException {
        StoreStats stats = store.stats();
        List<String> lines = new ArrayList<>();
        lines.add("capture: %d seen, %d stored, %d rejected as no-ops, %d unconfirmed, %d dropped"
                .formatted(
                        capture.captured(),
                        consumer.recordsStored(),
                        capture.rejectedUnchanged(),
                        capture.unconfirmed(),
                        capture.dropped()));
        lines.add("queue: %d records waiting, %d frames written, journal forced to %d"
                .formatted(consumer.pendingRecords(), consumer.framesWritten(), consumer.forcedLsn()));
        lines.add("store: %d rows hot, %d rows in %d shards, %d bytes, %d gaps"
                .formatted(
                        stats.hotRows(),
                        stats.sealedRows(),
                        stats.shardCount(),
                        stats.bytesOnDisk(),
                        stats.gapCount()));
        lines.add("dictionaries: %d worlds, %d block states, %d actors"
                .formatted(worlds.size(), states.size(), actors.size()));
        if (consumer.storeFailures() > 0) {
            lines.add("errors: %d storage failures since start — check the server log"
                    .formatted(consumer.storeFailures()));
        }
        lines.add("recovery: " + recovery.describe());
        lines.add("limitations in this build: block states are captured at material granularity,"
                + " block entities and container contents are not captured yet, and writes use the public"
                + " block API rather than the flagged internal path");
        return lines;
    }

    @Override
    public void close() {
        try {
            consumer.flushAndSeal(30_000);
        } catch (StoreException e) {
            logger.warn("Could not flush cleanly on shutdown: {}", e.getMessage());
        }
        consumer.stop();
        try {
            consumerThread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            // The clean-close marker is how the next start knows it does not need a crash gap.
            journal.appendCleanClose(System.currentTimeMillis());
            journal.close();
        } catch (IOException e) {
            logger.warn("Could not close the journal cleanly: {}", e.getMessage());
        }
        capture.close();
        try {
            store.close();
        } catch (StoreException e) {
            logger.warn("Could not close the store cleanly: {}", e.getMessage());
        }
        logger.info("Trace stopped; data directory {}", directory);
    }

    /** What recovery found on the way up. */
    public record RecoveryReport(long journalRecords, long ringRecords, boolean uncleanShutdown, List<String> notes) {

        public String describe() {
            if (notes.isEmpty()) {
                return uncleanShutdown ? "the last run did not close cleanly, but nothing was outstanding" : "clean";
            }
            return String.join("; ", notes);
        }
    }
}
