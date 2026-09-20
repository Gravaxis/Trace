/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.api.TraceApi;
import in.gravaxis.trace.harness.HarnessResult;
import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The M0 scenario: Trace loads on this server, enables, and publishes its service registration.
 *
 * <p>Trivial on purpose. It is what turns "the jar compiles" into "the jar runs on Paper and on
 * Folia", which is the whole M0 definition of done.
 */
public final class BootScenario implements Scenario {

    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        HarnessResult result = context.result();
        Plugin trace = Bukkit.getPluginManager().getPlugin("Trace");
        result.require(trace != null, "Trace is not installed on this server");
        if (trace == null) {
            return CompletableFuture.completedFuture(null);
        }
        result.detail("trace.version", trace.getPluginMeta().getVersion());
        result.require(trace.isEnabled(), "Trace is installed but not enabled");

        RegisteredServiceProvider<TraceApi> registration =
                Bukkit.getServicesManager().getRegistration(TraceApi.class);
        result.require(registration != null, "Trace did not register its API with the ServicesManager");
        if (registration != null) {
            int apiVersion = registration.getProvider().apiVersion();
            result.detail("trace.apiVersion", apiVersion)
                    .require(apiVersion >= 1, "Trace reported an implausible API version: " + apiVersion);
        }
        return CompletableFuture.completedFuture(null);
    }
}
