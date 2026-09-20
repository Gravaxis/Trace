/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness.scenarios;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * A stand-in player, so that a CI server with nobody connected can still fire the events a player
 * would.
 *
 * <p>This is a limitation worth stating plainly rather than hiding in a helper: the scenario
 * exercises Trace's listener, pipeline, storage and rollback, but not the server's own block-break
 * path. A protocol-level client that actually connects is the honest version, and it is the M4
 * answer; until then every result that uses this says so in its details.
 *
 * <p>The proxy answers only what a capture handler asks of a player — its identity — and throws for
 * anything else, so a test can never quietly depend on behaviour this does not have.
 */
final class HarnessPlayers {

    private static final UUID HARNESS_PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String NAME = "#harness";

    private static final Player PLAYER = (Player)
            Proxy.newProxyInstance(HarnessPlayers.class.getClassLoader(), new Class<?>[] {Player.class}, new Handler());

    private HarnessPlayers() {}

    /** Fires a break event for a block, exactly as the server would before removing it. */
    static void fireBlockBreak(Block block) {
        Bukkit.getPluginManager().callEvent(new BlockBreakEvent(block, PLAYER));
    }

    /**
     * Places a block and fires the event, in the server's order.
     *
     * <p>The order is the point: the server applies the block first and reports the state it
     * replaced, so a capture that confirms itself by reading the world at tick end sees a change.
     * Firing the event first would make a correct logger discard the record — and would make this
     * test lie about which path works.
     */
    static void fireBlockPlace(Block block, Material material) {
        BlockState replaced = block.getState();
        Block against = block.getWorld().getBlockAt(block.getX(), block.getY() - 1, block.getZ());
        block.setType(material, false);
        Bukkit.getPluginManager()
                .callEvent(new BlockPlaceEvent(
                        block, replaced, against, new ItemStack(material), PLAYER, true, EquipmentSlot.HAND));
    }

    private static final class Handler implements InvocationHandler {

        @Override
        // Identity is the right equality for a proxy: there is exactly one harness player.
        @SuppressWarnings("ReferenceEquality")
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> HARNESS_PLAYER;
                case "getName", "toString" -> NAME;
                case "hashCode" -> HARNESS_PLAYER.hashCode();
                case "equals" -> proxy == args[0];
                case "isOp", "hasPermission" -> true;
                default ->
                    throw new UnsupportedOperationException(
                            "The harness player has no " + method.getName() + "(); if capture needs it, the scenario"
                                    + " needs a real client rather than a wider proxy");
            };
        }
    }
}
