/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaintenanceConfigTest {
    @TempDir
    Path directory;

    @Test
    void preservesForeignNullValues() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "config-version: 1\nforeign-null: null\n");
        MaintenanceConfig.load(file, s -> {});
        assertThat(Files.readString(file)).contains("foreign-null: null");
    }

    @Test
    void rendersTemplatePreservesValuesAndForeignKeysAndBacksUpOldVersion() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(
                file, "config-version: 0\nmaintenance-interval: 2m\nenabled: false\nforeign: {nested: kept}\n");
        var messages = new java.util.ArrayList<String>();
        var policy = MaintenanceConfig.load(file, messages::add);
        assertThat(policy.intervalMillis()).isEqualTo(120_000);
        assertThat(policy.retentionMillis()).isZero();
        String rendered = Files.readString(file);
        assertThat(rendered)
                .startsWith("config-version: 1\n")
                .contains("# Cooperative deadline", "enabled: false", "nested: kept");
        assertThat(messages).anyMatch(s -> s.contains("Foreign")).anyMatch(s -> s.contains("Migrated"));
        try (var files = Files.list(directory)) {
            assertThat(files.filter(p -> p.getFileName().toString().contains("bak-v0"))
                            .count())
                    .isEqualTo(1);
        }
        assertThat(MaintenanceConfig.load(file, s -> {})).isEqualTo(policy);
        assertThat(Files.readString(file)).isEqualTo(rendered);
    }

    @Test
    void refusesAllInvalidValuesAndFutureVersionsWithoutChangingFile() throws Exception {
        Path file = directory.resolve("config.yml");
        String invalid =
                "config-version: 99\nmaintenance-enabled: maybe\nmaintenance-max-shards: 1\nmaintenance-budget: -1ms\n";
        Files.writeString(file, invalid);
        assertThatThrownBy(() -> MaintenanceConfig.load(file, s -> {}))
                .hasMessageContaining("config-version")
                .hasMessageContaining("maintenance-enabled")
                .hasMessageContaining("maintenance-max-shards")
                .hasMessageContaining("maintenance-budget");
        assertThat(Files.readString(file)).isEqualTo(invalid);
    }
}
