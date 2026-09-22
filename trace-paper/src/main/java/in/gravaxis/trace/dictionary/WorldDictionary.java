/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.storage.StoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Maps worlds to the small integer ids that fit in a record, keyed by the world's own identifier.
 *
 * <p>Keyed by UUID rather than name: a renamed world is the same world, and a new world reusing an
 * old name is not. Ids are assigned at startup for every loaded world, so the capture path only
 * ever reads an already-populated map.
 */
public final class WorldDictionary {

    private static final String FILE_NAME = "worlds.dict";

    private final Path file;
    private final DictionaryFile writer;
    private final Map<UUID, Integer> idsByWorld = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> worldsById = new ConcurrentHashMap<>();
    private volatile int nextId;

    private WorldDictionary(Path file, Map<UUID, Integer> loaded, DictionaryFile writer) {
        this.file = file;
        this.writer = writer;
        loaded.forEach((uuid, id) -> {
            idsByWorld.put(uuid, id);
            worldsById.put(id, uuid);
        });
        this.nextId = loaded.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    public static WorldDictionary load(Path directory) throws StoreException {
        return load(directory, DictionaryFile.DEFAULT);
    }

    static WorldDictionary load(Path directory, DictionaryFile writer) throws StoreException {
        Path file = directory.resolve(FILE_NAME);
        Map<UUID, Integer> loaded = new LinkedHashMap<>();
        var seenIds = new java.util.HashSet<Integer>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int equals = line.indexOf('=');
                    if (equals < 1) throw new IllegalArgumentException("Invalid world row");
                    UUID uuid = UUID.fromString(line.substring(0, equals));
                    int id = Integer.parseInt(line.substring(equals + 1));
                    if (id < 0 || id > EventRecords.MAX_WORLD_ID || loaded.containsKey(uuid) || !seenIds.add(id))
                        throw new IllegalArgumentException("Duplicate or invalid world id");
                    loaded.put(uuid, id);
                }
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Reason.DISK, "Could not read " + file, e);
        } catch (IllegalArgumentException e) {
            throw new StoreException(StoreException.Reason.CORRUPT, "Invalid world dictionary " + file, e);
        }
        return new WorldDictionary(file, loaded, writer);
    }

    /** Blocking startup registration; live registration queues a detached UUID for the worker. */
    public void register(World world) throws StoreException {
        register(world.getUID());
    }

    /** Blocking; never touches a live world. */
    public synchronized void register(UUID uuid) throws StoreException {
        if (idsByWorld.containsKey(uuid)) {
            return;
        }
        if (nextId > EventRecords.MAX_WORLD_ID)
            throw new StoreException(StoreException.Reason.CORRUPT, "World dictionary id space exhausted");
        int id = nextId;
        save(uuid, id);
        worldsById.put(id, uuid);
        idsByWorld.put(uuid, id);
        nextId++;
    }

    /** The id of a world, or -1 if it has not been registered. */
    public int idOf(World world) {
        return idOf(world.getUID());
    }

    public int idOf(UUID world) {
        Integer id = idsByWorld.get(world);
        return id == null ? -1 : id;
    }

    public @Nullable UUID uuidOf(int id) {
        return worldsById.get(id);
    }

    /** The world an id names, or null if it is not loaded right now. */
    public @Nullable World worldOf(int id) {
        UUID uuid = worldsById.get(id);
        return uuid == null ? null : Bukkit.getWorld(uuid);
    }

    public int size() {
        return idsByWorld.size();
    }

    private void save(UUID proposedWorld, int proposedId) throws StoreException {
        StringBuilder out = new StringBuilder(idsByWorld.size() * 40);
        idsByWorld.forEach((uuid, id) -> out.append(uuid).append('=').append(id).append('\n'));
        out.append(proposedWorld).append('=').append(proposedId).append('\n');
        writer.replace(file, out.toString());
    }
}
