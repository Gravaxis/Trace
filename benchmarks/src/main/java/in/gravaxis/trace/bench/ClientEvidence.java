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

/** Collects only the named real-client gate; old synthetic reports cannot satisfy it. */
public final class ClientEvidence {
    private ClientEvidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = MeasurementRun.create(
                root.resolve("benchmarks/results/client-capture"), ":benchmarks:clientCaptureEvidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        Files.copy(root.resolve("gradle/servers.properties"), output.resolve("servers.properties"));
        for (String platform : List.of("Paper", "Folia")) {
            Path result =
                    root.resolve("trace-test-harness/build/test-servers/client" + platform + "/harness-result.json");
            String text = Files.readString(result);
            if (!text.contains("\"status\": \"PASS\"")
                    || !text.contains("\"scenario\": \"client-capture\"")
                    || !text.contains("\"proof.complete\": \"true\""))
                throw new IllegalStateException("Incomplete client capture proof: " + platform);
            Files.copy(result, output.resolve("client" + platform + ".json"));
        }
        Path unit = root.resolve(
                "trace-test-harness/build/test-results/test/TEST-in.gravaxis.trace.harness.scenarios.ClientCaptureOracleTest.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var suite = factory.newDocumentBuilder().parse(unit.toFile()).getDocumentElement();
        if (!suite.getAttribute("failures").equals("0")
                || !suite.getAttribute("errors").equals("0")
                || !suite.getAttribute("skipped").equals("0")
                || Integer.parseInt(suite.getAttribute("tests")) < 2)
            throw new IllegalStateException("Oracle challenge tests did not pass");
        Files.copy(unit, output.resolve(unit.getFileName()));
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"scope\":\"Oracle challenges and serial loopback client Paper/Folia capture\",\"limits\":\"Offline creative client shares pinned server codecs; material-level capture; no production performance or independent protocol compatibility claim.\"}\n");
        System.out.println("Generated client capture evidence: " + output);
    }
}
