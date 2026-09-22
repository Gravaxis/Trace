/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import in.gravaxis.trace.storage.StoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final DictionaryFile writer;
    private final Map<UUID, Integer> idsByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, String> namesById = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> playersById = new ConcurrentHashMap<>();
    private long nextId = FIRST_PLAYER_ID;

    private ActorDictionary(Path file, DictionaryFile writer) {
        this.file = file;
        this.writer = writer;
    }

    public static ActorDictionary load(Path directory) throws StoreException {
        return load(directory, DictionaryFile.DEFAULT);
    }

    static ActorDictionary load(Path directory, DictionaryFile writer) throws StoreException {
        Path file = directory.resolve(FILE_NAME);
        ActorDictionary dictionary = new ActorDictionary(file, writer);
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 3 || !validName(parts[2]))
                        throw new IllegalArgumentException("Invalid actor row");
                    int id = Integer.parseInt(parts[0]);
                    UUID uuid = UUID.fromString(parts[1]);
                    if (id < FIRST_PLAYER_ID
                            || dictionary.idsByPlayer.containsKey(uuid)
                            || dictionary.playersById.containsKey(id))
                        throw new IllegalArgumentException("Duplicate or reserved actor id");
                    dictionary.idsByPlayer.put(uuid, id);
                    dictionary.playersById.put(id, uuid);
                    dictionary.namesById.put(id, parts[2]);
                    dictionary.nextId = Math.max(dictionary.nextId, (long) id + 1);
                }
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Reason.DISK, "Could not read " + file, e);
        } catch (IllegalArgumentException e) {
            throw new StoreException(StoreException.Reason.CORRUPT, "Invalid actor dictionary " + file, e);
        }
        return dictionary;
    }

    /**
     * The id for a player, assigning one if this is the first time.
     *
     * <p>Blocking: called by the storage worker, never from a player event. The forward map is
     * published last so capture can only see an id whose identity has survived persistence.
     */
    public synchronized int register(UUID player, String name) throws StoreException {
        if (!validName(name)) throw new StoreException(StoreException.Reason.CORRUPT, "Invalid actor name");
        Integer existing = idsByPlayer.get(player);
        if (existing != null && name.equals(namesById.get(existing))) return existing;
        if (existing == null && nextId > Integer.MAX_VALUE)
            throw new StoreException(StoreException.Reason.CORRUPT, "Actor dictionary id space exhausted");
        int id = existing == null ? (int) nextId : existing;
        save(id, player, name);
        playersById.put(id, player);
        namesById.put(id, name);
        idsByPlayer.put(player, id);
        if (existing == null) nextId++;
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

    static boolean validName(String name) {
        boolean fieldsSafe = !name.isEmpty()
                && name.length() <= 256
                && name.indexOf('\t') < 0
                && name.indexOf('\n') < 0
                && name.indexOf('\r') < 0
                && name.indexOf('\0') < 0;
        if (!fieldsSafe) return false;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == name.length() || !Character.isLowSurrogate(name.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    private void save(int proposedId, UUID proposedPlayer, String proposedName) throws StoreException {
        StringBuilder out = new StringBuilder(idsByPlayer.size() * 64);
        playersById.forEach((id, uuid) -> {
            if (id == proposedId) return;
            out.append(id)
                    .append('\t')
                    .append(uuid)
                    .append('\t')
                    .append(namesById.getOrDefault(id, ""))
                    .append('\n');
        });
        out.append(proposedId)
                .append('\t')
                .append(proposedPlayer)
                .append('\t')
                .append(proposedName)
                .append('\n');
        writer.replace(file, out.toString());
    }
}
