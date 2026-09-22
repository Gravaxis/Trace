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

/** Rejects missing named failure branches before publishing scoped integration evidence. */
public final class PayloadIntegrationEvidence {
    private PayloadIntegrationEvidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output = MeasurementRun.create(
                root.resolve("benchmarks/results/payload-integration"), ":benchmarks:payloadIntegrationEvidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        Files.copy(root.resolve("gradle/servers.properties"), output.resolve("servers.properties"));
        check(
                root,
                output,
                "trace-core",
                "payloadJournalTest",
                "in.gravaxis.trace.core.journal.CaptureEnvelopeTest",
                Set.of(
                        "exactOddBytesLegacyAndRestartSaltReplayTogether()",
                        "validChecksumUnknownEnvelopeRefusesReopenWithoutTruncation()",
                        "partialJournalWritePoisonsWriterUntilReopen()"));
        check(
                root,
                output,
                "trace-storage-sqlite",
                "payloadIntegrationTest",
                "in.gravaxis.trace.storage.sqlite.PayloadIntegrationTest",
                Set.of(
                        "firstLossWithOnlyOnePersistedBoundRefusesTheWholeWindow()",
                        "transactionAndGapFailuresRetainQueueUntilSuccessfulRetry()",
                        "journalFailureDoesNotAcknowledgeOrPublishTheEvent()",
                        "realProcessKillsRecoverExactPayloadAndLossBoundsAtEveryAnnouncedBoundary()"));
        check(
                root,
                output,
                "trace-storage-sqlite",
                "payloadIntegrationTest",
                "in.gravaxis.trace.storage.sqlite.SqliteEventStoreContractTest",
                Set.of(
                        "capturedEventPayloadAndWatermarkAreAtomicAndRetriesSurviveMaintenance()",
                        "rollbackPreflightRefusesNonBlockHistoryWithoutPayload()"));
        check(
                root,
                output,
                "trace-storage-sqlite",
                "payloadIntegrationTest",
                "in.gravaxis.trace.storage.sqlite.StorageMigrationTest",
                Set.of("refusesNewerFormatBeforeCreatingTablesAndReleasesLock()"));
        check(
                root,
                output,
                "trace-paper",
                "payloadConsumerTest",
                "in.gravaxis.trace.pipeline.PayloadConsumerTest",
                Set.of("failedPayloadBlocksLaterRingWatermarkAndFlushUntilRetrySucceeds()"));
        for (String platform : new String[] {"Paper", "Folia"}) {
            for (String prefix : new String[] {"payload", "client", "integration", "resume"}) {
                String scenario =
                        switch (prefix) {
                            case "payload" -> "payload-handoff";
                            case "client" -> "client-capture";
                            case "resume" -> "rollback-resume";
                            default -> "block-break-rollback";
                        };
                Path input = root.resolve(
                        "trace-test-harness/build/test-servers/" + prefix + platform + "/harness-result.json");
                String text = Files.readString(input);
                if (!text.contains("\"status\": \"PASS\"")
                        || !text.contains("\"scenario\": \"" + scenario + "\"")
                        || ((prefix.equals("payload") || prefix.equals("client"))
                                && !text.contains("\"proof.complete\": \"true\"")))
                    throw new IllegalStateException("Incomplete pinned server proof: " + prefix + platform);
                Files.copy(input, output.resolve(prefix + platform + ".json"));
            }
        }
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"scope\":\"Atomic opaque payload handoff, named failure and process-kill boundaries, shared contract, serial Paper/Folia transport and existing client/rollback regression\",\"limits\":\"Already durable dictionary ids; synthetic payload producers, no NBT or natural payload capture. Power loss and arbitrary-instruction crashes unproven. Payload allocation and performance not measured.\"}\n");
        System.out.println("Generated payload integration evidence: " + output);
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
            throw new IllegalStateException("Missing or failed named proof: " + name);
        Files.copy(input, output.resolve(input.getFileName()));
    }
}
