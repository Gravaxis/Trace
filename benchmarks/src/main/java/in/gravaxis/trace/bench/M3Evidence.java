/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

/** Copies only named harness artifacts after validating success; never scans server data or logs. */
public final class M3Evidence {
    private M3Evidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = MeasurementRun.create(root.resolve("benchmarks/results/m3-validation"), ":benchmarks:m3Evidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        Files.copy(root.resolve("gradle/servers.properties"), output.resolve("servers.properties"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String module : List.of("trace-core", "trace-storage-sqlite", "trace-paper")) {
            Path reports = root.resolve(module + "/build/test-results/test");
            try (var files = Files.list(reports)) {
                var xmls = files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                                && p.toString().endsWith(".xml"))
                        .sorted()
                        .toList();
                if (xmls.isEmpty()) throw new IllegalStateException("Missing unit reports: " + module);
                for (Path file : xmls) {
                    var suite =
                            factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    if (!suite.getAttribute("failures").equals("0")
                            || !suite.getAttribute("errors").equals("0")
                            || Integer.parseInt(suite.getAttribute("tests")) < 1
                            || !suite.getAttribute("skipped").equals("0"))
                        throw new IllegalStateException("Unsuccessful or vacuous test suite: " + file.getFileName());
                    Files.copy(file, output.resolve(module + "-" + file.getFileName()));
                }
            }
        }
        for (String platform : List.of("Paper", "Folia")) {
            for (String scenario : List.of("integration", "resume", "purge", "quarantine", "scheduled")) {
                Path file = root.resolve(
                        "trace-test-harness/build/test-servers/" + scenario + platform + "/harness-result.json");
                if (!Files.readString(file).contains("\"status\": \"PASS\""))
                    throw new IllegalStateException("Scenario did not pass");
                Files.copy(file, output.resolve(scenario + platform + ".json"));
            }
            for (String crash : List.of("crashRollback", "crashJournal")) {
                Path file = root.resolve("trace-test-harness/build/crash-results/" + crash + platform + ".json");
                if (!Files.readString(file).contains("\"failures\": 0"))
                    throw new IllegalStateException("Crash rig did not pass");
                Files.copy(file, output.resolve(crash + platform + ".json"));
                Path runs = root.resolve("trace-test-harness/build/test-servers/" + crash + platform);
                try (var directories = Files.list(runs)) {
                    for (Path iteration : directories
                            .filter(p -> p.getFileName().toString().startsWith("iteration-"))
                            .toList()) {
                        Path result = iteration.resolve("harness-result.json");
                        if (!Files.readString(result).contains("\"status\": \"PASS\""))
                            throw new IllegalStateException("Crash verification did not pass");
                        Files.copy(result, output.resolve(crash + platform + "-" + iteration.getFileName() + ".json"));
                    }
                }
            }
        }
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"limits\":\"Synthetic harness. Tests assert their own exercised branches. Not power-loss or production workload proof.\"}\n");
        System.out.println("Generated M3 evidence: " + output);
    }
}
