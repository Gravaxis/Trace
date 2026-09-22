/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.capture;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.record.Cause;
import in.gravaxis.trace.core.record.RecordKind;
import in.gravaxis.trace.dictionary.ActorDictionary;
import in.gravaxis.trace.dictionary.BlockStateDictionary;
import in.gravaxis.trace.dictionary.DictionaryUpdates;
import in.gravaxis.trace.dictionary.WorldDictionary;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The capture surface for block changes.
 *
 * <p>Handlers run at {@code MONITOR} with {@code ignoreCancelled}, but later handlers and server
 * processing can still change the outcome. They read coordinates, look two integers up in
 * arrays, and stage. No block-data string, no block snapshot, no map that can allocate — those are
 * what turn a logger into a tax on every block break.
 *
 * <p>Whether a staged record becomes history is decided at {@link ServerTickEndEvent}, by looking
 * at the world again. See ADR-0014: Trace logs changes, not attempts.
 */
public final class BlockCaptureListener implements Listener {

    private final CaptureService capture;
    private final WorldDictionary worlds;
    private final BlockStateDictionary states;
    private final ActorDictionary actors;
    private final DictionaryUpdates dictionaryUpdates;
    private final CaptureService.StateReader reader = this::stateAt;

    public BlockCaptureListener(
            CaptureService capture,
            WorldDictionary worlds,
            BlockStateDictionary states,
            ActorDictionary actors,
            DictionaryUpdates dictionaryUpdates) {
        this.capture = capture;
        this.worlds = worlds;
        this.states = states;
        this.actors = actors;
        this.dictionaryUpdates = dictionaryUpdates;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        int worldId = worlds.idOf(block.getWorld());
        int actorId = actors.idOf(event.getPlayer().getUniqueId());
        if (worldId < 0 || actorId == ActorDictionary.UNKNOWN) {
            capture.rejectMissingDependency();
            return;
        }
        capture.captureBlockChange(
                worldId,
                block.getX(),
                block.getY(),
                block.getZ(),
                states.idOf(block.getType()),
                actorId,
                Cause.BREAKING.id(),
                RecordKind.BLOCK.id());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        int worldId = worlds.idOf(block.getWorld());
        int actorId = actors.idOf(event.getPlayer().getUniqueId());
        if (worldId < 0 || actorId == ActorDictionary.UNKNOWN) {
            capture.rejectMissingDependency();
            return;
        }
        capture.captureBlockChange(
                worldId,
                block.getX(),
                block.getY(),
                block.getZ(),
                // The state the server already captured for the replaced block; no snapshot is
                // taken here.
                states.idOf(event.getBlockReplacedState().getType()),
                actorId,
                Cause.PLACING.id(),
                RecordKind.BLOCK.id());
    }

    /**
     * Decides which of this thread's staged records were real.
     *
     * <p>Fires per region on Folia and once per tick on Paper, always on the thread that owns the
     * region, which is what makes reading the block here legal and cheap.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        capture.confirmStaged(reader);
    }

    /** Enqueue detached identity; the storage worker publishes the id after persistence. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        dictionaryUpdates.actor(
                event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private int stateAt(int worldId, int x, int y, int z) {
        World world = worlds.worldOf(worldId);
        if (world == null
                || !Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)
                || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return CaptureService.UNKNOWN_STATE;
        }
        try {
            return states.idOf(world.getType(x, y, z));
        } catch (RuntimeException e) {
            // A failed read is missing knowledge, not an unchanged block. ADR-0024 turns this
            // sentinel into counted loss and a gap before rollback can trust this window.
            return CaptureService.UNKNOWN_STATE;
        }
    }
}
