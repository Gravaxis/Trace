/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Runs one scenario against a live server and reports the outcome to the build.
 *
 * <p>The scenario is chosen with {@code -Dtrace.harness.scenario=<name>}. With no property set the
 * harness stays idle, so the same jar can sit in a development server without doing anything.
 *
 * <p>Everything here runs through the global region scheduler, which is the only scheduler that
 * behaves identically on Paper and on Folia — the harness has to be as Folia-correct as the plugin
 * it tests.
 */
public final class TraceHarness extends JavaPlugin {

    private static final String SCENARIO_PROPERTY = "trace.harness.scenario";
    private static final String RESULT_FILE = "harness-result.json";
    private static final String MARKER = "[TRACE-HARNESS]";

    /** Ticks to wait after enable so that the server finishes loading before a scenario starts. */
    private static final long START_DELAY_TICKS = 20L;

    private static final Map<String, Scenario> SCENARIOS = Map.of("boot", new BootScenario());

    @Override
    public void onEnable() {
        String scenario = System.getProperty(SCENARIO_PROPERTY, "").trim();
        if (scenario.isEmpty()) {
            getSLF4JLogger().info("No scenario requested ({} unset); harness idle.", SCENARIO_PROPERTY);
            return;
        }
        getSLF4JLogger().info("Scenario '{}' queued.", scenario);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> runScenario(scenario), START_DELAY_TICKS);
    }

    private void runScenario(String name) {
        HarnessResult result = new HarnessResult(name);
        Scenario scenario = SCENARIOS.get(name);
        if (scenario == null) {
            result.failure("Unknown scenario '" + name + "'. Known scenarios: " + SCENARIOS.keySet());
        } else {
            try {
                scenario.run(this, result);
            } catch (Throwable t) {
                result.failure("Scenario threw " + t.getClass().getName() + ": " + t.getMessage());
                result.detail("exception", stackTrace(t));
            }
        }
        report(result);
        Bukkit.shutdown();
    }

    private void report(HarnessResult result) {
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
    }

    private static String stackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
