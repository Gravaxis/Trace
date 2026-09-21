/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import in.gravaxis.trace.harness.scenarios.BlockBreakRollbackScenario;
import in.gravaxis.trace.harness.scenarios.BlockChurnScenario;
import in.gravaxis.trace.harness.scenarios.BootScenario;
import in.gravaxis.trace.harness.scenarios.CrashJournalVerifyScenario;
import in.gravaxis.trace.harness.scenarios.CrashJournalWriteScenario;
import in.gravaxis.trace.harness.scenarios.CrashVerifyScenario;
import in.gravaxis.trace.harness.scenarios.CrashWriteScenario;
import in.gravaxis.trace.harness.scenarios.RollbackResumeScenario;
import io.papermc.paper.ServerBuildInfo;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Runs one scenario against a live server and reports the outcome to the build.
 *
 * <p>The scenario is chosen with {@code -Dtrace.harness.scenario=<name>} and parameterised with
 * {@code -Dtrace.harness.params=k=v,k=v}. With no scenario set the harness stays idle, so the same
 * jar can sit in a development server doing nothing.
 *
 * <p>Everything starts on the global region scheduler, the one scheduler that behaves the same on
 * Paper and on Folia: the harness has to be as Folia-correct as the plugin it tests.
 */
public final class TraceHarness extends JavaPlugin {

    /** Prefix the build watches for in the server log. */
    public static final String MARKER = "[TRACE-HARNESS]";

    private static final String SCENARIO_PROPERTY = "trace.harness.scenario";
    private static final String PARAMS_PROPERTY = "trace.harness.params";
    private static final String RESULT_FILE = "harness-result.json";

    /** Ticks to wait after enable so the server finishes loading before a scenario starts. */
    private static final long START_DELAY_TICKS = 20L;

    private static final Map<String, Scenario> SCENARIOS = Map.ofEntries(
            Map.entry("boot", new BootScenario()),
            Map.entry("block-churn", new BlockChurnScenario()),
            Map.entry("block-break-rollback", new BlockBreakRollbackScenario()),
            Map.entry("crash-write", new CrashWriteScenario()),
            Map.entry("crash-verify", new CrashVerifyScenario()),
            Map.entry("crash-journal-write", new CrashJournalWriteScenario()),
            Map.entry("crash-journal-verify", new CrashJournalVerifyScenario()),
            Map.entry("rollback-resume", new RollbackResumeScenario()),
            Map.entry("rollback-crash-prepare", new RollbackResumeScenario()),
            Map.entry("rollback-crash-write", new RollbackResumeScenario()),
            Map.entry("rollback-crash-verify", new RollbackResumeScenario()),
            Map.entry("storage-purge", new RollbackResumeScenario()),
            Map.entry("storage-scheduled", new RollbackResumeScenario()),
            Map.entry("storage-quarantine", new RollbackResumeScenario()));

    @Override
    public void onEnable() {
        String scenario = System.getProperty(SCENARIO_PROPERTY, "").trim();
        if (scenario.isEmpty()) {
            getSLF4JLogger().info("No scenario requested ({} unset); harness idle.", SCENARIO_PROPERTY);
            return;
        }
        getSLF4JLogger().info("Scenario '{}' queued.", scenario);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> start(scenario), START_DELAY_TICKS);
    }

    private void start(String name) {
        Map<String, String> params = parseParams(System.getProperty(PARAMS_PROPERTY, ""));
        HarnessResult result = new HarnessResult(name, params);
        describeServer(result);
        ScenarioContext context =
                new ScenarioContext(this, params, result, Path.of("").toAbsolutePath());

        Scenario scenario = SCENARIOS.get(name);
        if (scenario == null) {
            result.failure("Unknown scenario '" + name + "'. Known scenarios: " + SCENARIOS.keySet());
            finish(result);
            return;
        }

        CompletableFuture<Void> done;
        try {
            done = scenario.run(context);
        } catch (Throwable t) {
            record(result, t);
            finish(result);
            return;
        }
        var _ = done.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                record(result, throwable);
            }
            // Back to the global region thread: shutting down is global state.
            Bukkit.getGlobalRegionScheduler().execute(this, () -> finish(result));
        });
    }

    /** Every result says which server produced it; a measurement without that is not evidence. */
    private static void describeServer(HarnessResult result) {
        ServerBuildInfo build = ServerBuildInfo.buildInfo();
        result.detail("server.brand", build.brandName())
                .detail("server.minecraftVersion", build.minecraftVersionId())
                .detail("server.version", Bukkit.getVersion())
                .detail("server.regionised", build.isBrandCompatible(Key.key("papermc", "folia")))
                .detail("java.version", Runtime.version().toString())
                .detail("heap.maxBytes", Runtime.getRuntime().maxMemory());
    }

    private static void record(HarnessResult result, Throwable t) {
        result.failure("Scenario threw " + t.getClass().getName() + ": " + t.getMessage());
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        result.detail("exception", writer.toString());
    }

    private void finish(HarnessResult result) {
        Path file = Path.of(RESULT_FILE).toAbsolutePath();
        @Nullable String writeFailure = null;
        try {
            Files.writeString(file, result.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            writeFailure = e.toString();
        }
        // The marker line is what a human reads in the server log; the JSON is what the build reads.
        getSLF4JLogger()
                .info(
                        "{} result={} scenario={} failures={}",
                        MARKER,
                        result.status(),
                        result.scenario(),
                        result.failures());
        if (writeFailure != null) {
            getSLF4JLogger().error("{} could not write {}: {}", MARKER, file, writeFailure);
        }
        Bukkit.shutdown();
    }

    private static Map<String, String> parseParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw.isBlank()) {
            return params;
        }
        for (String pair : raw.split(",", -1)) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                params.put(
                        pair.substring(0, equals).trim(),
                        pair.substring(equals + 1).trim());
            }
        }
        return params;
    }
}
