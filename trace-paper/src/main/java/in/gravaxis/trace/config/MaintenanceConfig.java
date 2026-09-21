/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.config;

import in.gravaxis.trace.storage.MaintenancePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** ADR-0005: preserve values and foreign keys while restoring the packaged comments each start. */
public final class MaintenanceConfig {
    private MaintenanceConfig() {}

    public static MaintenancePolicy load(Path file, Consumer<String> log) throws IOException {
        String template;
        try (var stream = MaintenanceConfig.class.getResourceAsStream("/config.yml")) {
            if (stream == null) throw new IOException("Missing packaged configuration template");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Map<String, @org.jspecify.annotations.Nullable Object> defaults = values(yaml, template);
        Map<String, @org.jspecify.annotations.Nullable Object> supplied =
                Files.exists(file) ? values(yaml, Files.readString(file)) : Map.of();
        Map<String, @org.jspecify.annotations.Nullable Object> merged = new LinkedHashMap<>(defaults);
        merged.putAll(supplied);
        List<String> errors = new ArrayList<>();
        long version = integer(merged, "config-version", 0, 1, errors);
        boolean enabled = true;
        Object toggle = merged.get("maintenance-enabled");
        if (toggle instanceof Boolean value) enabled = value;
        else errors.add("maintenance-enabled must be boolean");
        long interval = duration(merged, "maintenance-interval", 1, Long.MAX_VALUE, errors);
        long rows = integer(merged, "maintenance-max-input-rows", 1, Long.MAX_VALUE, errors);
        long shards = integer(merged, "maintenance-max-shards", 2, 1024, errors);
        long budget = duration(merged, "maintenance-budget", 1, 60_000, errors);
        long retention = duration(merged, "retention-age", 0, Long.MAX_VALUE, errors);
        if (!errors.isEmpty()) throw new IOException("Configuration refused: " + String.join("; ", errors));
        MaintenancePolicy policy = new MaintenancePolicy(enabled, interval, rows, (int) shards, budget, retention);
        Map<String, @org.jspecify.annotations.Nullable Object> foreign = new LinkedHashMap<>(supplied);
        defaults.keySet().forEach(foreign::remove);
        StringBuilder rendered = new StringBuilder();
        merged.put("config-version", 1);
        for (String line : template.lines().toList()) {
            int colon = line.indexOf(':');
            if (!line.startsWith("#") && colon > 0) {
                String key = line.substring(0, colon);
                rendered.append(key).append(": ").append(merged.get(key)).append('\n');
                if (!supplied.containsKey(key)) log.accept("Added configuration key " + key);
            } else rendered.append(line).append('\n');
        }
        if (!foreign.isEmpty()) {
            log.accept(
                    "Foreign configuration keys preserved: shared plugins/trace/config.yml may belong to another plugin");
            rendered.append("# Preserved foreign keys (shared-file collision; ADR-0005).\n")
                    .append(yaml.dumpAsMap(foreign));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.exists(file) && version < 1) {
            Files.copy(
                    file,
                    file.resolveSibling(file.getFileName() + ".bak-v" + version + "-" + System.currentTimeMillis()));
            log.accept("Migrated configuration version " + version + " to 1; backup retained");
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, rendered);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return policy;
    }

    private static Map<String, @org.jspecify.annotations.Nullable Object> values(Yaml yaml, String text)
            throws IOException {
        Object loaded;
        try {
            loaded = yaml.load(text);
        } catch (RuntimeException e) {
            throw new IOException("Invalid YAML configuration", e);
        }
        if (loaded == null) return Map.of();
        if (!(loaded instanceof Map<?, ?> map)) throw new IOException("Configuration must be a mapping");
        Map<String, @org.jspecify.annotations.Nullable Object> values = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("Configuration requires string keys");
            values.put(key, entry.getValue());
        }
        return values;
    }

    private static long integer(
            Map<String, @org.jspecify.annotations.Nullable Object> values,
            String key,
            long min,
            long max,
            List<String> errors) {
        try {
            long value = Long.parseLong(String.valueOf(values.get(key)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            errors.add(key + " must be an integer in [" + min + "," + max + "]");
            return min;
        }
    }

    private static long duration(
            Map<String, @org.jspecify.annotations.Nullable Object> values,
            String key,
            long min,
            long max,
            List<String> errors) {
        String text = String.valueOf(values.get(key));
        var matcher = java.util.regex.Pattern.compile("([0-9]+)(ms|s|m|h|d)").matcher(text);
        try {
            if (!matcher.matches()) throw new IllegalArgumentException();
            long multiplier =
                    switch (matcher.group(2)) {
                        case "ms" -> 1;
                        case "s" -> 1000;
                        case "m" -> 60_000;
                        case "h" -> 3_600_000;
                        default -> 86_400_000;
                    };
            long value = Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
            if (value < min || value > max) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException | ArithmeticException e) {
            errors.add(key + " requires a duration within bounds");
            return min;
        }
    }
}
