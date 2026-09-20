/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace;

import in.gravaxis.trace.api.TraceApi;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>M0 deliberately does nothing but come up, identify the platform it is running on, and publish
 * the service registration that consumers will later look up. Capture, storage and rollback arrive
 * in M2, after the benchmark and crash harness exists to measure them.
 */
public final class Trace extends JavaPlugin implements TraceApi {

    /** Generation of the published API surface. */
    public static final int API_VERSION = 1;

    private static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    private boolean regionised;

    @Override
    public void onEnable() {
        ServerBuildInfo build = ServerBuildInfo.buildInfo();
        this.regionised = build.isBrandCompatible(FOLIA_BRAND);

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
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    /**
     * Whether the server ticks regions on several threads (Folia) rather than one main thread.
     *
     * <p>Trace schedules through the region, entity, async and global-region schedulers either way;
     * this is used for reporting and for sizing, never to pick a different code path for world
     * access.
     *
     * @return true on a Folia-compatible server
     */
    public boolean isRegionised() {
        return regionised;
    }
}
