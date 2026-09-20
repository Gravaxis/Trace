/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import in.gravaxis.trace.Trace;
import in.gravaxis.trace.core.geom.BlockBox;
import in.gravaxis.trace.rollback.RollbackSummary;
import in.gravaxis.trace.runtime.TraceRuntime;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * The command surface: everything under {@code /trace}, the way {@code /co} works, so that muscle
 * memory transfers and no other plugin's label is taken (ADR-0002).
 *
 * <p>This is the M2 shape — status and a radius rollback, enough to drive the engine — not the
 * grammar the build spec describes. The filter words, pagination and inspector arrive with the
 * milestone that designs them.
 */
public final class TraceCommand {

    private static final int DEFAULT_RADIUS = 10;
    private static final int DEFAULT_SECONDS = 600;

    private TraceCommand() {}

    public static LiteralCommandNode<CommandSourceStack> build(Trace plugin) {
        return Commands.literal("trace")
                .requires(source -> source.getSender().hasPermission("trace.status"))
                .then(status(plugin))
                .then(rollback(plugin))
                .executes(context -> {
                    context.getSource()
                            .getSender()
                            .sendMessage(Component.text("Usage: /trace status | /trace rollback <radius> <seconds>")
                                    .color(NamedTextColor.GRAY));
                    return 1;
                })
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> status(Trace plugin) {
        return Commands.literal("status")
                .requires(source -> source.getSender().hasPermission("trace.status"))
                .executes(context -> {
                    TraceRuntime runtime = plugin.runtime();
                    if (runtime == null) {
                        context.getSource()
                                .getSender()
                                .sendMessage(
                                        Component.text("Trace is not running.").color(NamedTextColor.RED));
                        return 0;
                    }
                    try {
                        for (String line : runtime.status()) {
                            context.getSource().getSender().sendMessage(Component.text(line));
                        }
                    } catch (Exception e) {
                        context.getSource()
                                .getSender()
                                .sendMessage(Component.text("Could not read status: " + e.getMessage())
                                        .color(NamedTextColor.RED));
                        return 0;
                    }
                    return 1;
                });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rollback(Trace plugin) {
        return Commands.literal("rollback")
                // Restricted: a rollback reached from a chat click or a book would be a serious
                // surprise, so the client asks first.
                .requires(Commands.restricted(source -> source.getSender().hasPermission("trace.rollback.blocks")))
                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 512))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86_400))
                                .executes(context -> runRollback(
                                        plugin,
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .executes(context -> runRollback(plugin, context.getSource(), DEFAULT_RADIUS, DEFAULT_SECONDS));
    }

    private static int runRollback(Trace plugin, CommandSourceStack source, int radius, int seconds) {
        TraceRuntime runtime = plugin.runtime();
        if (runtime == null) {
            source.getSender()
                    .sendMessage(Component.text("Trace is not running.").color(NamedTextColor.RED));
            return 0;
        }
        Entity executor = source.getExecutor();
        Location location = executor != null ? executor.getLocation() : source.getLocation();
        long since = System.currentTimeMillis() - seconds * 1000L;

        source.getSender()
                .sendMessage(Component.text("Rolling back %d blocks around you, %d seconds of history..."
                                .formatted(radius, seconds))
                        .color(NamedTextColor.GRAY));

        // Storage and region work: never on the thread that ran the command.
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            RollbackSummary summary;
            try {
                summary = runtime.rollback()
                        .rollback(
                                location.getWorld(),
                                BlockBox.around(
                                        location.getBlockX(), location.getBlockY(), location.getBlockZ(), radius),
                                since,
                                System.currentTimeMillis() + 1);
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Rollback failed", e);
                source.getSender()
                        .sendMessage(Component.text("Rollback failed: " + e.getMessage())
                                .color(NamedTextColor.RED));
                return;
            }
            List<Component> message = summary.refused()
                    ? List.of(Component.text(summary.describe()).color(NamedTextColor.YELLOW))
                    : List.of(Component.text(summary.describe()).color(NamedTextColor.GREEN));
            message.forEach(source.getSender()::sendMessage);
        });
        return 1;
    }
}
