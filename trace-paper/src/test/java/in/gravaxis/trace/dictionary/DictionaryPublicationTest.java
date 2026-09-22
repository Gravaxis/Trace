/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.dictionary;

import static org.assertj.core.api.Assertions.*;

import in.gravaxis.trace.core.capture.CaptureService;
import in.gravaxis.trace.core.journal.JournalWriter;
import in.gravaxis.trace.pipeline.StoreConsumer;
import in.gravaxis.trace.storage.MaintenancePolicy;
import in.gravaxis.trace.storage.StoreException;
import in.gravaxis.trace.storage.sqlite.SqliteEventStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DictionaryPublicationTest {
    @TempDir
    Path directory;

    static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000123");
    static final UUID WORLD = UUID.fromString("00000000-0000-4000-8000-000000000456");

    @Test
    void invalidNamesCannotPoisonTheRegistrationHead() throws Exception {
        var actors = ActorDictionary.load(directory);
        var updates = new DictionaryUpdates(actors, WorldDictionary.load(directory));
        for (String name :
                List.of("", "tab\tname", "new\nline", String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00)))
            assertThat(updates.actor(PLAYER, name)).isFalse();
        assertThat(updates.pending()).isZero();
        assertThat(updates.accepted()).isZero();
        assertThat(updates.rejected()).isEqualTo(5);
        assertThat(updates.actor(PLAYER, "valid")).isTrue();
        assertThat(Files.exists(directory.resolve("actors.dict"))).isFalse();
        updates.flush();
        assertThat(updates.completed()).isEqualTo(1);
        assertThat(ActorDictionary.load(directory).nameOf(16)).isEqualTo("valid");
    }

    @Test
    void realProcessKillsAtForceReplaceAndPublishPreserveRecoverableIdentity() throws Exception {
        for (String kind : List.of("actor", "world"))
            for (String phase : List.of("forced", "replaced", "published")) {
                Path area = Files.createDirectory(directory.resolve(kind + "-" + phase));
                Process child = new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-cp",
                                System.getProperty("trace.testClasspath"),
                                DictionaryCrashWorker.class.getName(),
                                area.toString(),
                                kind,
                                phase)
                        .redirectErrorStream(true)
                        .redirectOutput(area.resolve("worker.log").toFile())
                        .start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!Files.exists(area.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline)
                        Thread.sleep(10);
                    assertThat(Files.exists(area.resolve("ready")))
                            .as(Files.readString(area.resolve("worker.log")))
                            .isTrue();
                    assertThat(Files.readString(area.resolve("ready"))).isEqualTo(kind + ":" + phase);
                    child.destroyForcibly();
                    assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.exitValue()).isNotZero();
                    for (int reopen = 0; reopen < 2; reopen++) {
                        if (kind.equals("actor")) {
                            var dictionary = ActorDictionary.load(area);
                            assertThat(dictionary.idOf(PLAYER)).isEqualTo(phase.equals("forced") ? 0 : 16);
                            if (!phase.equals("forced")) {
                                assertThat(dictionary.playerOf(16)).isEqualTo(PLAYER);
                                assertThat(dictionary.nameOf(16)).isEqualTo("crash-fixture");
                            }
                        } else {
                            var dictionary = WorldDictionary.load(area);
                            assertThat(dictionary.idOf(PLAYER)).isEqualTo(phase.equals("forced") ? -1 : 0);
                            if (!phase.equals("forced"))
                                assertThat(dictionary.uuidOf(0)).isEqualTo(PLAYER);
                        }
                    }
                } finally {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                        assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
                    }
                }
            }
    }

    @Test
    void forceAndReplaceFailuresCannotPublishIdentityAndRetryPreservesIds() throws Exception {
        for (String phase : List.of("forced", "replaced")) {
            Path area = Files.createDirectory(directory.resolve(phase));
            AtomicBoolean fail = new AtomicBoolean(true);
            var writer = new DictionaryFile(at -> {
                if (at.equals(phase) && fail.get()) throw new IOException(phase);
            });
            var actors = ActorDictionary.load(area, writer);
            var worlds = WorldDictionary.load(area, writer);
            assertThatThrownBy(() -> actors.register(PLAYER, "old-name")).isInstanceOf(StoreException.class);
            assertThatThrownBy(() -> worlds.register(WORLD)).isInstanceOf(StoreException.class);
            assertThat(actors.idOf(PLAYER)).isEqualTo(ActorDictionary.UNKNOWN);
            assertThat(actors.size()).isZero();
            assertThat(worlds.idOf(WORLD)).isEqualTo(-1);
            assertThat(worlds.size()).isZero();
            assertThat(ActorDictionary.load(area).idOf(PLAYER)).isEqualTo(phase.equals("forced") ? 0 : 16);
            assertThat(WorldDictionary.load(area).idOf(WORLD)).isEqualTo(phase.equals("forced") ? -1 : 0);
            fail.set(false);
            assertThat(actors.register(PLAYER, "old-name")).isEqualTo(16);
            worlds.register(WORLD);
            assertThat(worlds.idOf(WORLD)).isZero();
            assertThat(actors.playerOf(16)).isEqualTo(PLAYER);
            assertThat(worlds.uuidOf(0)).isEqualTo(WORLD);
            fail.set(true);
            assertThatThrownBy(() -> actors.register(PLAYER, "new-name")).isInstanceOf(StoreException.class);
            assertThat(actors.nameOf(16)).isEqualTo("old-name");
            fail.set(false);
            assertThat(actors.register(PLAYER, "new-name")).isEqualTo(16);
            var reopened = ActorDictionary.load(area);
            assertThat(reopened.nameOf(reopened.idOf(PLAYER))).isEqualTo("new-name");
            assertThat(reopened.register(UUID.randomUUID(), "next-player")).isEqualTo(17);
        }
    }

    @Test
    void malformedDuplicateAndExhaustedDictionariesRefuseWithoutChangingBytes() throws Exception {
        for (String text : List.of(
                "bad-row\n",
                "1\t" + PLAYER + "\tname\n",
                "16\t" + PLAYER + "\tname\n16\t" + WORLD + "\tname\n",
                "16\t" + PLAYER + "\tname\n17\t" + PLAYER + "\tname\n")) {
            Files.writeString(directory.resolve("actors.dict"), text);
            assertThatThrownBy(() -> ActorDictionary.load(directory))
                    .isInstanceOfSatisfying(
                            StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CORRUPT));
            assertThat(Files.readString(directory.resolve("actors.dict"))).isEqualTo(text);
        }
        Files.writeString(directory.resolve("actors.dict"), Integer.MAX_VALUE + "\t" + PLAYER + "\tlast\n");
        var actors = ActorDictionary.load(directory);
        assertThat(actors.register(PLAYER, "renamed")).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> actors.register(WORLD, "overflow")).isInstanceOf(StoreException.class);
        assertThat(actors.idOf(WORLD)).isZero();
        for (String text : List.of(
                "invalid\n",
                WORLD + "=-1\n",
                WORLD + "=4096\n",
                WORLD + "=0\n" + PLAYER + "=0\n",
                WORLD + "=0\n" + WORLD + "=1\n")) {
            Files.writeString(directory.resolve("worlds.dict"), text);
            assertThatThrownBy(() -> WorldDictionary.load(directory))
                    .isInstanceOfSatisfying(
                            StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CORRUPT));
            assertThat(Files.readString(directory.resolve("worlds.dict"))).isEqualTo(text);
        }
        Files.writeString(directory.resolve("worlds.dict"), WORLD + "=4095\n");
        var worlds = WorldDictionary.load(directory);
        assertThatThrownBy(() -> worlds.register(PLAYER)).isInstanceOf(StoreException.class);
        assertThat(worlds.idOf(PLAYER)).isEqualTo(-1);
        for (String text :
                List.of("invalid\n", "STONE=-1\n", "STONE=16777216\n", "STONE=1\nDIRT=1\n", "STONE=1\nSTONE=2\n")) {
            Files.writeString(directory.resolve("block-states.dict"), text);
            assertThatThrownBy(() -> BlockStateDictionary.load(directory))
                    .isInstanceOfSatisfying(
                            StoreException.class, e -> assertThat(e.reason()).isEqualTo(StoreException.Reason.CORRUPT));
            assertThat(Files.readString(directory.resolve("block-states.dict"))).isEqualTo(text);
        }
    }

    @Test
    void boundedAdmissionAndFailedHeadRetainIdentityUntilPersistenceSucceeds() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        var actors = ActorDictionary.load(directory, new DictionaryFile(phase -> {
            if (fail.get()) throw new IOException("injected");
        }));
        var worlds = WorldDictionary.load(directory);
        var updates = new DictionaryUpdates(actors, worlds, 2);
        assertThat(updates.actor(PLAYER, "player")).isTrue();
        assertThat(updates.world(WORLD)).isTrue();
        assertThat(updates.actor(UUID.randomUUID(), "overflow")).isFalse();
        assertThat(updates.accepted()).isEqualTo(2);
        assertThat(updates.rejected()).isEqualTo(1);
        assertThatThrownBy(() -> updates.drain(2)).isInstanceOf(StoreException.class);
        assertThat(updates.pending()).isEqualTo(2);
        assertThat(updates.completed()).isZero();
        assertThat(actors.idOf(PLAYER)).isZero();
        assertThat(worlds.idOf(WORLD)).isEqualTo(-1);
        fail.set(false);
        assertThat(updates.drain(1)).isEqualTo(1);
        assertThat(actors.idOf(PLAYER)).isEqualTo(16);
        assertThat(worlds.idOf(WORLD)).isEqualTo(-1);
        updates.flush();
        assertThat(updates.completed()).isEqualTo(2);
        assertThat(updates.pending()).isZero();
        assertThat(WorldDictionary.load(directory).idOf(WORLD)).isZero();
        assertThat(updates.actor(PLAYER, "changed-name")).isTrue();
        updates.flush();
        assertThat(ActorDictionary.load(directory).nameOf(16)).isEqualTo("changed-name");
    }

    @Test
    void consumerFailureThenRetryKeepsMissingIdentityGappedAndPublishedIdentityDurable() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        var actors = ActorDictionary.load(directory, new DictionaryFile(phase -> {
            if (fail.get()) throw new IOException("injected");
        }));
        var updates = new DictionaryUpdates(actors, WorldDictionary.load(directory));
        try (var capture = new CaptureService(directory.resolve("rings"), 16);
                var journal = JournalWriter.open(directory.resolve("journal"), 4096, 1);
                var store = SqliteEventStore.open(directory.resolve("store"))) {
            long before = System.currentTimeMillis();
            assertThat(updates.actor(PLAYER, "name")).isTrue();
            assertThat(actors.idOf(PLAYER)).isZero();
            capture.rejectMissingDependency();
            var consumer = new StoreConsumer(
                    capture,
                    journal,
                    store,
                    org.slf4j.LoggerFactory.getLogger(getClass()),
                    200,
                    60000,
                    MaintenancePolicy.defaults(),
                    List.of(),
                    updates);
            Thread worker = new Thread(consumer, "dictionary-consumer-test");
            worker.start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (consumer.storeFailures() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(consumer.storeFailures()).isPositive();
                assertThat(updates.pending()).isEqualTo(1);
                assertThat(updates.completed()).isZero();
                assertThat(actors.idOf(PLAYER)).isZero();
                assertThatThrownBy(() -> consumer.flushAndSeal(100)).isInstanceOf(StoreException.class);
                fail.set(false);
                consumer.flushAndSeal(10000);
                assertThat(updates.pending()).isZero();
                assertThat(updates.completed()).isEqualTo(1);
                assertThat(actors.idOf(PLAYER))
                        .isEqualTo(ActorDictionary.load(directory).idOf(PLAYER))
                        .isPositive();
                assertThat(capture.droppedDependency()).isEqualTo(1);
                assertThat(capture.published()).isZero();
                assertThat(store.stats().hotRows()).isZero();
                assertThat(store.gapsBetween(before, Long.MAX_VALUE))
                        .singleElement()
                        .satisfies(gap -> {
                            assertThat(gap.fromMillis()).isLessThanOrEqualTo(before);
                            assertThat(gap.toMillis()).isGreaterThanOrEqualTo(before);
                            assertThat(gap.droppedCount()).isEqualTo(1);
                        });
            } finally {
                consumer.stop();
                worker.join(10000);
                assertThat(worker.isAlive()).isFalse();
            }
        }
    }

    @Test
    void concurrentAdmissionCannotExceedBoundAndEveryAcceptedIdentityPublishes() throws Exception {
        var actors = ActorDictionary.load(directory);
        var updates = new DictionaryUpdates(actors, WorldDictionary.load(directory), 4);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                UUID uuid = new UUID(0, i + 1);
                calls.add(pool.submit(() -> {
                    start.await();
                    return updates.actor(uuid, "actor");
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(4);
            assertThat(updates.pending()).isEqualTo(4);
            assertThat(updates.rejected()).isEqualTo(8);
            updates.flush();
            assertThat(updates.completed()).isEqualTo(4);
            assertThat(ActorDictionary.load(directory).size()).isEqualTo(4);
        }
    }
}
