/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import in.gravaxis.trace.harness.Scenario;
import in.gravaxis.trace.harness.ScenarioContext;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/** Synthetic transport proof on the pinned runtime; it does not assert natural payload capture. */
public final class PayloadHandoffScenario implements Scenario {
    @Override
    public CompletableFuture<Void> run(ScenarioContext context) {
        var done = new CompletableFuture<Void>();
        var trace = Bukkit.getPluginManager().getPlugin("Trace");
        if (trace == null || !trace.isEnabled())
            return CompletableFuture.failedFuture(new IllegalStateException("Trace absent"));
        String world = Bukkit.getWorlds().getFirst().getName();
        Bukkit.getAsyncScheduler().runNow(context.plugin(), task -> {
            try {
                String facts = (String) trace.getClass()
                        .getMethod("payloadHandoffForTest", String.class)
                        .invoke(trace, world);
                context.result().detail("payload.facts", facts);
                context.result()
                        .require(
                                facts.equals("ACCEPTED|OVERSIZED|0|00ff03|2,64,3,STONE,DIRT,2,2,1,true,1|true|true"),
                                "Wrong handoff branch, bytes, full row, gap, acknowledgement or rollback refusal: "
                                        + facts);
                context.result()
                        .detail(
                                "proof.complete",
                                context.result().status() == in.gravaxis.trace.harness.HarnessResult.Status.PASS);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }
}
