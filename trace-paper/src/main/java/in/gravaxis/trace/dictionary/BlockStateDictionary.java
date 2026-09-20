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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Maps block states to the dense integer ids that go on disk, and back again.
 *
 * <p>Ids are assigned once and persisted <em>by name</em>. The tempting shortcut — storing the
 * server's own registry index, or a {@link Material} ordinal — breaks the first time the game adds
 * a block: every id already written would then name something else. The mapping file is the
 * authority, and the array the hot path uses is derived from it at startup.
 *
 * <p>Lookups on the capture path are an array index by ordinal: no hashing, no allocation, no
 * string ever touched on a tick thread.
 *
 * <p><strong>Fidelity in this build:</strong> states are interned at material granularity. A rolled
 * back block comes back as the right block, but properties such as facing or waterlogging are not
 * yet captured; that arrives with the block-entity and full-state work in M4, which is also where
 * the internal NBT path lands. {@code /trace status} says so, rather than letting an operator
 * assume otherwise.
 */
public final class BlockStateDictionary {

    private static final String FILE_NAME = "block-states.dict";

    private final Path file;
    private final Map<String, Integer> idsByName = new LinkedHashMap<>();
    private final int[] idByOrdinal;
    private final Material[] materialById;

    private BlockStateDictionary(Path file, Map<String, Integer> loaded) {
        this.file = file;
        this.idsByName.putAll(loaded);

        Material[] materials = Material.values();
        this.idByOrdinal = new int[materials.length];
        Arrays.fill(idByOrdinal, -1);

        // Assign ids to anything this build knows and the file does not, in name order so that two
        // servers of the same version end up with the same mapping.
        List<Material> missing = new ArrayList<>();
        for (Material material : materials) {
            if (!idsByName.containsKey(material.name())) {
                missing.add(material);
            }
        }
        missing.sort((a, b) -> a.name().compareTo(b.name()));
        int next = idsByName.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (Material material : missing) {
            idsByName.put(material.name(), next++);
        }

        int maxId =
                idsByName.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        this.materialById = new Material[maxId + 1];
        for (Map.Entry<String, Integer> entry : idsByName.entrySet()) {
            Material material = Material.getMaterial(entry.getKey());
            int id = entry.getValue();
            if (material != null) {
                idByOrdinal[material.ordinal()] = id;
                materialById[id] = material;
            }
            // A name this build does not know is kept in the file and simply has no material: an
            // older server must not renumber ids that a newer one already wrote.
        }
    }

    /** Loads the mapping from the data directory, creating and extending it as needed. */
    public static BlockStateDictionary load(Path directory) {
        Path file = directory.resolve(FILE_NAME);
        Map<String, Integer> loaded = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        loaded.put(line.substring(0, equals), Integer.parseInt(line.substring(equals + 1)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
        BlockStateDictionary dictionary = new BlockStateDictionary(file, loaded);
        dictionary.save();
        return dictionary;
    }

    /** The id for a material. An array index: safe to call on a tick thread. */
    public int idOf(Material material) {
        return idByOrdinal[material.ordinal()];
    }

    /** The material an id names, or null if this build does not know it. */
    public @Nullable Material materialOf(int id) {
        return id >= 0 && id < materialById.length ? materialById[id] : null;
    }

    public int size() {
        return idsByName.size();
    }

    private void save() {
        StringBuilder out = new StringBuilder(idsByName.size() * 24);
        idsByName.forEach((name, id) -> out.append(name).append('=').append(id).append('\n'));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
