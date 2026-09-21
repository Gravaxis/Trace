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

/** Named branch tests prevent unrelated passing suites from masquerading as queue evidence. */
public final class PayloadQueueEvidence {
    private PayloadQueueEvidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = MeasurementRun.create(
                root.resolve("benchmarks/results/payload-queue"), ":benchmarks:payloadQueueEvidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        Path unit = root.resolve(
                "trace-core/build/test-results/payloadQueueTest/TEST-in.gravaxis.trace.core.capture.BoundedPayloadQueueTest.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var suite = factory.newDocumentBuilder().parse(unit.toFile()).getDocumentElement();
        Set<String> expected = Set.of(
                "acknowledgementControlsReuseAndCannotReleaseAnotherGeneration()",
                "rejectionBranchesAreDistinctAndBoundsOnlyWiden()",
                "existingInvalidFilesAreRefusedWithoutReinitializing()",
                "corruptCursorsAndPayloadLengthCannotBeAcknowledged()",
                "ticketExhaustionDoesNotWrapIntoOldAcknowledgements()",
                "separateProducerAndConsumerNeverObserveTornOrReorderedPayloads()",
                "actualProcessKillPreservesPublishedEntryAndOverflowBounds()");
        Set<String> actual = new HashSet<>();
        var cases = suite.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            var test = (org.w3c.dom.Element) cases.item(i);
            if (!test.getAttribute("classname").equals("in.gravaxis.trace.core.capture.BoundedPayloadQueueTest"))
                throw new IllegalStateException("Unexpected queue test class");
            actual.add(test.getAttribute("name"));
        }
        if (!suite.getAttribute("failures").equals("0")
                || !suite.getAttribute("errors").equals("0")
                || !suite.getAttribute("skipped").equals("0")
                || !actual.equals(expected)
                || cases.getLength() != expected.size()
                || Integer.parseInt(suite.getAttribute("tests")) != expected.size())
            throw new IllegalStateException("Incomplete bounded payload queue proof");
        Files.copy(unit, output.resolve(unit.getFileName()));
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"scope\":\"Bounded queue branch, concurrency, corruption refusal and process-kill tests\",\"limits\":\"Transport primitive only; not live capture or journal/GapRecord integration. Power loss unproven. Allocation and performance not measured.\"}\n");
        System.out.println("Generated payload queue evidence: " + output);
    }
}
