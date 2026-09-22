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
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

/** Requires the named loss and publication branches, not merely a green aggregate test count. */
public final class ConfirmationEvidence {
    private ConfirmationEvidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = MeasurementRun.create(
                root.resolve("benchmarks/results/confirmation"), ":benchmarks:confirmationEvidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        check(
                root,
                output,
                "trace-core",
                "confirmationTest",
                "in.gravaxis.trace.core.capture.CaptureServiceTest",
                Set.of(
                        "staging exhaustion gaps the loss instead of inventing an unconfirmed row",
                        "repeatedPositionKeepsFirstBeforeAndFinalAfterAndResetsIndex()",
                        "roundTripToFirstStateRejectsEveryObservationEvenWithMixedAttribution()",
                        "ambiguousChangedAttributionGapsAllObservations()",
                        "unreadableAndExplicitFlushNeverPublishInventedRows()",
                        "packedFieldOverflowAndInvalidReaderStatesAreGappedBeforeEncoding()",
                        "hashCollisionsAndIdenticalCoordinatesInDifferentWorldsRemainDistinct()"));
        check(
                root,
                output,
                "trace-paper",
                "confirmationTest",
                "in.gravaxis.trace.pipeline.ConfirmationConsumerTest",
                Set.of("flushRefusesFailedConfirmationGapAndRetryPersistsItWithoutInventedRows()"));
        var allocation = Set.of(
                "a tick of events that changed nothing allocates nothing at all",
                "a tick of real changes, packed and written to the ring, allocates nothing at all",
                "the counter itself can see an allocation, so a zero above means something",
                "coalescedPublicationAllocatesNothingAndPublishesOneRowPerPosition()",
                "ambiguousObservationAllocatesNothingAndGapsBothInputs()",
                "missingConfirmationAllocatesNothingAndRecordsLoss()",
                "invalidPostStateAllocatesNothingAndCannotBePacked()",
                "unconfirmedFlushAllocatesNothingAndOnlyGaps()",
                "stagingExhaustionAllocatesNothingAndDoesNotPublish()");
        for (String task : new String[] {"confirmationAllocationTest", "testWithoutEscapeAnalysis"})
            check(root, output, "benchmarks", task, "in.gravaxis.trace.bench.ExactAllocationTest", allocation);
        for (String file : new String[] {"jmh-result.json", "allocation-gate.json"})
            Files.copy(root.resolve("benchmarks/build/jmh/" + file), output.resolve(file));
        Files.copy(root.resolve("gradle/servers.properties"), output.resolve("servers.properties"));
        for (String platform : new String[] {"Paper", "Folia"}) {
            for (String prefix : new String[] {"client", "integration", "resume"}) {
                Path input = root.resolve(
                        "trace-test-harness/build/test-servers/" + prefix + platform + "/harness-result.json");
                String contents = Files.readString(input);
                String scenario =
                        switch (prefix) {
                            case "client" -> "client-capture";
                            case "resume" -> "rollback-resume";
                            default -> "block-break-rollback";
                        };
                if (!contents.contains("\"status\": \"PASS\"")
                        || !contents.contains("\"scenario\": \"" + scenario + "\"")
                        || (prefix.equals("client") && !contents.contains("\"proof.complete\": \"true\"")))
                    throw new IllegalStateException("Missing pinned regression: " + prefix + platform);
                Files.copy(input, output.resolve(prefix + platform + ".json"));
            }
        }
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"scope\":\"Net tick observation and loss branch tests; consumer flush gap refusal/retry; exact normal/cold allocation and JMH gate; serial existing client and rollback regressions\",\"limits\":\"New edge branches tested without a server; server gates retain existing break/place scope. No full-state, payload listeners or complete M4 proof. Listener cost and power-loss durability not measured.\"}\n");
        System.out.println("Generated confirmation evidence: " + output);
    }

    private static void check(Path root, Path output, String module, String task, String name, Set<String> required)
            throws Exception {
        Path input = root.resolve(module + "/build/test-results/" + task + "/TEST-" + name + ".xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var suite = factory.newDocumentBuilder().parse(input.toFile()).getDocumentElement();
        var cases = suite.getElementsByTagName("testcase");
        Set<String> actual = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++)
            actual.add(((org.w3c.dom.Element) cases.item(i)).getAttribute("name"));
        if (!suite.getAttribute("failures").equals("0")
                || !suite.getAttribute("errors").equals("0")
                || !suite.getAttribute("skipped").equals("0")
                || !actual.containsAll(required))
            throw new IllegalStateException("Missing named confirmation proof: " + name);
        Files.copy(input, output.resolve(module + "-" + task + "-" + input.getFileName()));
    }
}
