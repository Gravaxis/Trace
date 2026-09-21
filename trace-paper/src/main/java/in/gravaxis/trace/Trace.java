/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace;

import in.gravaxis.trace.api.TraceApi;
import in.gravaxis.trace.command.TraceCommand;
import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.dictionary.ActorDictionary;
import in.gravaxis.trace.rollback.RollbackSummary;
import in.gravaxis.trace.runtime.TraceRuntime;
import in.gravaxis.trace.storage.GapRecord;
import in.gravaxis.trace.storage.MutationBatch;
import in.gravaxis.trace.storage.MutationCursor;
import in.gravaxis.trace.storage.RollbackOperation;
import in.gravaxis.trace.storage.ScanPlan;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Plugin entry point.
 *
 * <p>Brings up the runtime — store, journal, rings, consumer — and registers the capture listener
 * and the command surface. Everything of substance lives in {@link TraceRuntime}; this class is the
 * part Bukkit knows about.
 */
public final class Trace extends JavaPlugin implements TraceApi {

    /** Blocking, bounded fixture scan; called only from the client harness's async task. */
    @ApiStatus.Internal
    public String clientCaptureRows(String worldName, int x, int y, int z) throws Exception {
        if (!System.getProperty("trace.harness.scenario", "").equals("client-capture"))
            throw new IllegalStateException("Only available in client capture harness");
        TraceRuntime current = runtime;
        World world = Bukkit.getWorld(worldName);
        if (current == null || world == null) throw new IllegalStateException("Runtime or world absent");
        current.consumer().flushAndSeal(30_000);
        ScanPlan plan = ScanPlan.of(
                current.worlds().idOf(world),
                new BlockBox(x, y, z, x + 5, y, z),
                0,
                System.currentTimeMillis() + 1000,
                ScanPlan.Order.OLDEST_FIRST);
        MutationBatch batch = new MutationBatch(plan.batchSize());
        StringBuilder out = new StringBuilder();
        int seen = 0;
        try (MutationCursor cursor = current.store().scan(plan)) {
            while (cursor.next(batch)) {
                for (int i = 0; i < batch.size(); i++) {
                    if (++seen > 16) throw new IllegalStateException("Client fixture unexpectedly large");
                    if (!out.isEmpty()) out.append(';');
                    out.append(batch.x(i))
                            .append(':')
                            .append(batch.y(i))
                            .append(':')
                            .append(batch.z(i))
                            .append(':')
                            .append(current.states().materialOf(batch.beforeState(i)))
                            .append(':')
                            .append(current.states().materialOf(batch.afterState(i)))
                            .append(':')
                            .append(current.actors().playerOf(batch.actorId(i)))
                            .append(':')
                            .append(batch.cause(i))
                            .append(':')
                            .append(batch.kind(i))
                            .append(':')
                            .append(batch.timestamp(i))
                            .append(':')
                            .append(batch.sequence(i))
                            .append(':')
                            .append(batch.chunkKey(i));
                }
            }
        }
        return out.toString();
    }

    /** Blocking harness seam, called asynchronously; only the consumer runs scheduled maintenance. */
    @ApiStatus.Internal
    public String scheduledMaintenanceForTest() throws Exception {
        if (!System.getProperty("trace.harness.scenario", "").equals("storage-scheduled"))
            throw new IllegalStateException("Only available in the scheduled maintenance harness");
        TraceRuntime current = runtime;
        if (current == null) throw new IllegalStateException("Not running");
        current.consumer().flushAndSeal(30_000);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (current.consumer().maintenanceCompleted() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
        return current.consumer().maintenanceStatus() + " shards="
                + current.store().stats().shardCount();
    }

    /** Creates a seal boundary between real captured batches; no maintenance is performed here. */
    @ApiStatus.Internal
    public String sealForScheduledTest() throws Exception {
        if (!System.getProperty("trace.harness.scenario", "").equals("storage-scheduled"))
            throw new IllegalStateException("Only available in the scheduled maintenance harness");
        TraceRuntime current = runtime;
        if (current == null) throw new IllegalStateException("Not running");
        current.consumer().flushAndSeal(30_000);
        return "sealed";
    }

    /** Generation of the published API surface. */
    public static final int API_VERSION = 1;

    private static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    private boolean regionised;
    private @Nullable TraceRuntime runtime;

    @Override
    public void onEnable() {
        ServerBuildInfo build = ServerBuildInfo.buildInfo();
        this.regionised = build.isBrandCompatible(FOLIA_BRAND);

        try {
            this.runtime =
                    TraceRuntime.start(this, getSLF4JLogger(), getDataFolder().toPath());
        } catch (Exception e) {
            // A logger that half-works is worse than one that is plainly off: an operator who
            // thinks they have history and does not is the person this plugin exists to protect.
            getSLF4JLogger().error("Trace could not start and will not capture anything", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Registered here rather than in a bootstrapper: handlers registered during enable run
        // after every plugin has enabled, which is what keeps the bare /trace label (ADR-0002).
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> event.registrar()
                                .register(
                                        TraceCommand.build(this),
                                        "Block history and rollback",
                                        java.util.List.of("tr")));

        getSLF4JLogger()
                .info(
                        "Trace {} enabled on {} {} ({} scheduling), Java {}",
                        getPluginMeta().getVersion(),
                        build.brandName(),
                        build.minecraftVersionId(),
                        regionised ? "regionised" : "single-threaded",
                        Runtime.version().feature());

        Bukkit.getServicesManager().register(TraceApi.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    /**
     * Whether the server ticks regions on several threads (Folia) rather than one main thread.
     *
     * <p>Trace schedules through the region, entity, async and global-region schedulers either way;
     * this is used for reporting, never to pick a different code path for world access.
     */
    public boolean isRegionised() {
        return regionised;
    }

    /** The running runtime, or null if Trace failed to start. */
    public @Nullable TraceRuntime runtime() {
        return runtime;
    }

    /**
     * Runs a rollback and returns a one-line summary.
     *
     * <p>A seam for the integration harness, which cannot depend on this module and drives Trace
     * reflectively. The command in {@link TraceCommand} is the real entry point, and the published
     * API takes this over in the milestone that designs it. Blocking: never call it from a tick
     * thread.
     */
    @ApiStatus.Internal
    public String runRollback(String worldName, int x, int y, int z, int radius, long sinceMillis) {
        TraceRuntime current = runtime;
        if (current == null) {
            return "refused: Trace is not running";
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "refused: no world named " + worldName;
        }
        try {
            RollbackSummary summary = current.rollback()
                    .rollback(
                            world,
                            BlockBox.around(x, y, z, radius),
                            System.currentTimeMillis() - sinceMillis,
                            System.currentTimeMillis() + 1,
                            ActorDictionary.HARNESS);
            return summary.describe();
        } catch (Exception e) {
            getSLF4JLogger().error("Rollback failed", e);
            return "failed: " + e;
        }
    }

    /**
     * Continues a rollback a previous run did not finish, and returns a one-line summary.
     *
     * <p>The same seam as {@link #runRollback}, for the same reason. Blocking.
     */
    @ApiStatus.Internal
    public String resumeRollback(long operationId) {
        TraceRuntime current = runtime;
        if (current == null) {
            return "refused: Trace is not running";
        }
        try {
            return current.rollback().resume(operationId).describe();
        } catch (Exception e) {
            getSLF4JLogger().error("Resuming rollback {} failed", operationId, e);
            return "failed: " + e;
        }
    }

    @ApiStatus.Internal
    public String compactStorage() throws Exception {
        TraceRuntime current = runtime;
        if (current == null) return "refused";
        current.consumer().flushAndSeal(30000);
        return "rewritten=" + current.store().compact();
    }

    @ApiStatus.Internal
    public String maintenanceForTest(String action) throws Exception {
        TraceRuntime current = runtime;
        if (current == null || !System.getProperty("trace.harness.scenario", "").startsWith("storage-"))
            return "refused";
        current.consumer().flushAndSeal(30000);
        int verified = current.store().verify().verified();
        long before = current.store().stats().sealedRows();
        if (action.equals("purge"))
            current.store()
                    .purgeActor(
                            current.actors().idOf(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001")),
                            0,
                            Long.MAX_VALUE);
        else if (action.equals("quarantine")) current.store().quarantineShard(1, "harness quarantine");
        else throw new IllegalArgumentException("Unknown maintenance action");
        return "verified=" + verified + " changed="
                + (before - current.store().stats().sealedRows()) + " gaps="
                + current.store().stats().gapCount();
    }

    @ApiStatus.Internal
    public String armRollbackCrash() {
        TraceRuntime current = runtime;
        if (current == null || !"rollback-crash-write".equals(System.getProperty("trace.harness.scenario")))
            return "refused";
        current.rollback().checkpointObserver(id -> {
            try {
                RollbackOperation operation = current.store().operation(id);
                if (operation == null || operation.progress().applied() <= 0 || operation.cursor() == null)
                    throw new IllegalStateException("Crash hook did not reach an applied checkpoint");
                // Let the consumer journal rollback's own writes before the kill; the stored
                // operation window ends before these writes, and must remain unchanged on resume.
                Thread.sleep(1000);
                getSLF4JLogger().info("[TRACE-HARNESS] ready rollback-checkpoint id={}", id);
                while (true) Thread.sleep(1000);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return "armed";
    }

    /**
     * Asks a running rollback to stop at its next chunk boundary.
     *
     * <p>A seam for the harness, which needs a rollback that really was interrupted in order to
     * test that resuming one works. Cancelling is per operation, so this cannot stop a different
     * rollback that happens to be running.
     */
    @ApiStatus.Internal
    public String cancelRollback(long operationId) {
        TraceRuntime current = runtime;
        if (current == null) {
            return "refused: Trace is not running";
        }
        current.rollback().cancel(operationId);
        return "cancelling " + operationId;
    }

    /**
     * One line per rollback that still has work left: {@code id state applied scanned}.
     *
     * <p>A seam for the harness, and the shape {@code /trace status} reports.
     */
    @ApiStatus.Internal
    public String unfinishedRollbacks() {
        TraceRuntime current = runtime;
        if (current == null) {
            return "unavailable";
        }
        try {
            StringBuilder out = new StringBuilder();
            for (RollbackOperation operation : current.rollback().unfinished()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append("id=%d state=%s applied=%d scanned=%d"
                        .formatted(
                                operation.id(),
                                operation.state(),
                                operation.progress().applied(),
                                operation.progress().scanned()));
            }
            return out.toString();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not list rollbacks", e);
            return "failed: " + e;
        }
    }

    /**
     * Every position the store holds in a box and window, as {@code x:y:z} separated by semicolons.
     *
     * <p>A seam for the crash rig, which has to answer one question after a restart: of the events
     * it knows happened, which ones did Trace keep? Returning positions rather than a count is the
     * point — "how many" cannot tell you <em>which</em> are missing, and the property being tested
     * is about each missing event individually.
     */
    @ApiStatus.Internal
    public String storedPositions(
            String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long from, long to) {
        TraceRuntime current = runtime;
        if (current == null) {
            return "refused: Trace is not running";
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "refused: no world named " + worldName;
        }
        int worldId = current.worlds().idOf(world);
        if (worldId < 0) {
            return "refused: no history for " + worldName;
        }
        try {
            current.consumer().flushAndSeal(30_000);
            ScanPlan plan = ScanPlan.of(
                    worldId, new BlockBox(minX, minY, minZ, maxX, maxY, maxZ), from, to, ScanPlan.Order.OLDEST_FIRST);
            StringBuilder out = new StringBuilder(1 << 16);
            MutationBatch batch = new MutationBatch(plan.batchSize());
            try (MutationCursor cursor = current.store().scan(plan)) {
                while (cursor.next(batch)) {
                    for (int i = 0; i < batch.size(); i++) {
                        if (!out.isEmpty()) {
                            out.append(';');
                        }
                        out.append(batch.x(i))
                                .append(':')
                                .append(batch.y(i))
                                .append(':')
                                .append(batch.z(i));
                    }
                }
            }
            return out.toString();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not list stored positions", e);
            return "failed: " + e;
        }
    }

    /**
     * Gaps overlapping a window, as {@code from-to:reason} separated by semicolons.
     *
     * <p>The other half of the crash rig's question: for every event Trace did not keep, is there a
     * recorded gap saying so? An uncovered loss is history with a silent hole in it.
     */
    @ApiStatus.Internal
    public String gapsBetween(long from, long to) {
        TraceRuntime current = runtime;
        if (current == null) {
            return "refused: Trace is not running";
        }
        try {
            StringBuilder out = new StringBuilder();
            for (GapRecord gap : current.store().gapsBetween(from, to)) {
                if (!out.isEmpty()) {
                    out.append(';');
                }
                out.append(gap.fromMillis())
                        .append('-')
                        .append(gap.toMillis())
                        .append(':')
                        .append(gap.reason());
            }
            return out.toString();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not list gaps", e);
            return "failed: " + e;
        }
    }

    /**
     * Capture counters as one line, for the integration harness.
     *
     * <p>Same seam as {@link #runRollback}: the harness cannot depend on this module, and
     * {@code /trace status} is the human-facing version of the same numbers.
     */
    @ApiStatus.Internal
    public String captureCounters() {
        TraceRuntime current = runtime;
        if (current == null) {
            return "unavailable";
        }
        return "captured=%d published=%d rejectedUnchanged=%d unconfirmed=%d dropped=%d outOfRange=%d stored=%d"
                .formatted(
                        current.capture().captured(),
                        current.capture().published(),
                        current.capture().rejectedUnchanged(),
                        current.capture().unconfirmed(),
                        current.capture().dropped(),
                        current.capture().outOfRange(),
                        current.consumer().recordsStored());
    }

    /** Ensures a world loaded after startup has an id before anything in it is captured. */
    public void registerWorld(World world) {
        TraceRuntime current = runtime;
        if (current != null) {
            current.registerWorld(world);
        }
    }
}
