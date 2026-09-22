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
import java.util.Set;

/** Named queue, persistence and client barriers prevent a green unrelated suite from closing this gate. */
public final class DictionaryEvidence {
    private DictionaryEvidence() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path output =
                MeasurementRun.create(root.resolve("benchmarks/results/dictionary"), ":benchmarks:dictionaryEvidence");
        Files.writeString(output.resolve("complete.json"), "{\"complete\":false}\n");
        ConfirmationEvidence.check(
                root,
                output,
                "trace-paper",
                "dictionaryTest",
                "in.gravaxis.trace.dictionary.DictionaryPublicationTest",
                Set.of(
                        "forceAndReplaceFailuresCannotPublishIdentityAndRetryPreservesIds()",
                        "invalidNamesCannotPoisonTheRegistrationHead()",
                        "malformedDuplicateAndExhaustedDictionariesRefuseWithoutChangingBytes()",
                        "boundedAdmissionAndFailedHeadRetainIdentityUntilPersistenceSucceeds()",
                        "consumerFailureThenRetryKeepsMissingIdentityGappedAndPublishedIdentityDurable()",
                        "concurrentAdmissionCannotExceedBoundAndEveryAcceptedIdentityPublishes()",
                        "realProcessKillsAtForceReplaceAndPublishPreserveRecoverableIdentity()"));
        for (String task : new String[] {"confirmationAllocationTest", "testWithoutEscapeAnalysis"})
            ConfirmationEvidence.check(
                    root,
                    output,
                    "benchmarks",
                    task,
                    "in.gravaxis.trace.bench.ExactAllocationTest",
                    Set.of(
                            "missingDictionaryDependencyAllocatesNothingAndOnlyGaps()",
                            "a tick of real changes, packed and written to the ring, allocates nothing at all",
                            "a tick of events that changed nothing allocates nothing at all",
                            "the counter itself can see an allocation, so a zero above means something"));
        for (String file : new String[] {"jmh-result.json", "allocation-gate.json"})
            Files.copy(root.resolve("benchmarks/build/jmh/" + file), output.resolve(file));
        Files.copy(root.resolve("gradle/servers.properties"), output.resolve("servers.properties"));
        for (String platform : new String[] {"Paper", "Folia"})
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
                        || !contents.contains(
                                "\"dictionary.writerLock\": \"second runtime refused before dictionary replacement\"")
                        || (prefix.equals("client")
                                && (!contents.contains("\"proof.complete\": \"true\"")
                                        || !contents.contains("\"area0.actorDictionary\": \"durable-before-actions\"")
                                        || !contents.contains(
                                                "\"area1.actorDictionary\": \"durable-before-actions\""))))
                    throw new IllegalStateException("Missing dictionary/runtime proof: " + prefix + platform);
                Files.copy(input, output.resolve(prefix + platform + ".json"));
            }
        Files.writeString(
                output.resolve("complete.json"),
                "{\"complete\":true,\"scope\":\"Bounded dictionary requests, persist-before-id publication, named fault and process-kill boundaries, missing dependency gaps, exact allocation and serial pinned client/rollback regressions\",\"limits\":\"Material ids remain legacy fidelity. Dynamic world-load event and loss under a real player's failed registration are not exercised. No full-state/NBT capture, arbitrary-instruction or power-loss proof. Dictionary/queue costs and listener allocation not measured.\"}\n");
        System.out.println("Generated dictionary evidence: " + output);
    }
}
