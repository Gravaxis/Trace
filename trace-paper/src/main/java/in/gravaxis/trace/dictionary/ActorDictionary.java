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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Maps whoever caused a change to the integer id that goes in the record.
 *
 * <p>Events carry an id, never an identity: that separation is what makes an erasure request
 * possible later without breaking rollback. Deleting the identity row leaves the history intact and
 * anonymous, because no event ever contained a name or a UUID.
 *
 * <p>Non-player actors (the environment, Trace's own rollbacks) get fixed ids that never collide
 * with a player's.
 */
public final class ActorDictionary {

    private static final String FILE_NAME = "actors.dict";

    /** Unknown or unattributed. */
    public static final int UNKNOWN = 0;

    /** Trace itself, writing to the world during a rollback. */
    public static final int TRACE = 1;

    /** The test harness, so its writes are distinguishable from a player's. */
    public static final int HARNESS = 2;

    private static final int FIRST_PLAYER_ID = 16;

    private final Path file;
    private final Map<UUID, Integer> idsByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, String> namesById = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> playersById = new ConcurrentHashMap<>();
    private int nextId = FIRST_PLAYER_ID;

    private ActorDictionary(Path file) {
        this.file = file;
    }

    public static ActorDictionary load(Path directory) {
        Path file = directory.resolve(FILE_NAME);
        ActorDictionary dictionary = new ActorDictionary(file);
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length == 3) {
                        int id = Integer.parseInt(parts[0]);
                        UUID uuid = UUID.fromString(parts[1]);
                        dictionary.idsByPlayer.put(uuid, id);
                        dictionary.playersById.put(id, uuid);
                        dictionary.namesById.put(id, parts[2]);
                        dictionary.nextId = Math.max(dictionary.nextId, id + 1);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        return dictionary;
    }

    /**
     * The id for a player, assigning one if this is the first time.
     *
     * <p>Called when a player joins, not while capturing: the capture path only ever reads.
     */
    public synchronized int register(UUID player, String name) {
        Integer existing = idsByPlayer.get(player);
        if (existing != null) {
            if (!name.equals(namesById.get(existing))) {
                // Names change; history keeps the id, and the current name is what gets displayed.
                namesById.put(existing, name);
                save();
            }
            return existing;
        }
        int id = nextId++;
        idsByPlayer.put(player, id);
        playersById.put(id, player);
        namesById.put(id, name);
        save();
        return id;
    }

    /** The id of a player already registered, or {@link #UNKNOWN}. */
    public int idOf(UUID player) {
        Integer id = idsByPlayer.get(player);
        return id == null ? UNKNOWN : id;
    }

    /** A human-readable name for an id. */
    public String nameOf(int id) {
        return switch (id) {
            case UNKNOWN -> "#unknown";
            case TRACE -> "#trace";
            case HARNESS -> "#harness";
            default -> {
                String name = namesById.get(id);
                yield name == null ? "#" + id : name;
            }
        };
    }

    public @Nullable UUID playerOf(int id) {
        return playersById.get(id);
    }

    public int size() {
        return idsByPlayer.size();
    }

    private void save() {
        StringBuilder out = new StringBuilder(idsByPlayer.size() * 64);
        playersById.forEach((id, uuid) -> out.append(id)
                .append('\t')
                .append(uuid)
                .append('\t')
                .append(namesById.getOrDefault(id, ""))
                .append('\n'));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
