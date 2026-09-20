/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private final Map<UUID, Integer> idsByWorld = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> worldsById = new ConcurrentHashMap<>();
    private volatile int nextId;

    private WorldDictionary(Path file, Map<UUID, Integer> loaded) {
        this.file = file;
        loaded.forEach((uuid, id) -> {
            idsByWorld.put(uuid, id);
            worldsById.put(id, uuid);
        });
        this.nextId = loaded.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    public static WorldDictionary load(Path directory) {
        Path file = directory.resolve(FILE_NAME);
        Map<UUID, Integer> loaded = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        loaded.put(
                                UUID.fromString(line.substring(0, equals)),
                                Integer.parseInt(line.substring(equals + 1)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        return new WorldDictionary(file, loaded);
    }

    /** Assigns ids to every loaded world. Called at startup and when a world is loaded. */
    public synchronized void register(World world) {
        if (idsByWorld.containsKey(world.getUID())) {
            return;
        }
        int id = nextId++;
        idsByWorld.put(world.getUID(), id);
        worldsById.put(id, world.getUID());
        save();
    }

    /** The id of a world, or -1 if it has not been registered. */
    public int idOf(World world) {
        Integer id = idsByWorld.get(world.getUID());
        return id == null ? -1 : id;
    }

    /** The world an id names, or null if it is not loaded right now. */
    public @Nullable World worldOf(int id) {
        UUID uuid = worldsById.get(id);
        return uuid == null ? null : Bukkit.getWorld(uuid);
    }

    public int size() {
        return idsByWorld.size();
    }

    private void save() {
        StringBuilder out = new StringBuilder(idsByWorld.size() * 40);
        idsByWorld.forEach((uuid, id) -> out.append(uuid).append('=').append(id).append('\n'));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
