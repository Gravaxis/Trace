/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package in.gravaxis.trace.api;

/**
 * Entry point to Trace, obtained from Bukkit's {@code ServicesManager}:
 *
 * <pre>{@code
 * RegisteredServiceProvider<TraceApi> registration =
 *         Bukkit.getServicesManager().getRegistration(TraceApi.class);
 * if (registration != null) {
 *     TraceApi trace = registration.getProvider();
 * }
 * }</pre>
 *
 * <p>Threading, once the corresponding methods exist: logging calls never block and are safe from
 * any thread; lookups block on storage and must be called off the server thread. Every method's
 * javadoc will repeat which of the two it is.
 *
 * <p>This interface is not to be implemented outside Trace.
 */
public interface TraceApi {

    /**
     * The API generation this server is running.
     *
     * <p>It increases when the API gains capabilities and resets no other expectation: a consumer
     * that needs a method added in generation <i>n</i> should check {@code apiVersion() >= n}
     * rather than inspecting the plugin version. Breaking changes to published signatures come with
     * a major version and a deprecation cycle, never with a silent bump here.
     *
     * @return the API generation, currently {@code 1}
     */
    int apiVersion();
}
