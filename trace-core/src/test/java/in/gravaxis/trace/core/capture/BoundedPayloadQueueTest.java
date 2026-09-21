/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gravaxis.trace.core.testing.PayloadQueueCrashWorker;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedPayloadQueueTest {
    @TempDir
    Path directory;

    @Test
    void acknowledgementControlsReuseAndCannotReleaseAnotherGeneration() throws Exception {
        Path file = directory.resolve("queue");
        try (var queue = BoundedPayloadQueue.open(file, 1, 8)) {
            byte[] original = {1, 2, -1};
            assertThat(queue.offer(11, 22, 33, 44, 7, original, 3, 100, 110))
                    .isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            original[0] = 9;
            assertThat(queue.offer(0, 0, 0, 0, 0, original, 3, 90, 120)).isEqualTo(BoundedPayloadQueue.Offer.FULL);
            var small = new BoundedPayloadQueue.Buffer(2);
            assertThat(queue.peek(small)).isEqualTo(BoundedPayloadQueue.Read.TOO_SMALL);
            assertThat(queue.acknowledge(0)).isFalse();
            var view = new BoundedPayloadQueue.Buffer(8);
            assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.READY);
            assertThat(view.payload()).startsWith((byte) 1, (byte) 2, (byte) -1);
            assertThat(view.length()).isEqualTo(3);
            assertThat(view.version()).isEqualTo(7);
            assertThat(view.fromMillis()).isEqualTo(100);
            assertThat(view.toMillis()).isEqualTo(110);
            for (int i = 0; i < 4; i++) assertThat(view.word(i)).isEqualTo((i + 1) * 11);
            assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.READY);
            assertThat(queue.pending()).isEqualTo(1);
            long oldTicket = view.ticket();
            assertThat(queue.acknowledge(oldTicket)).isTrue();
            assertThat(queue.offer(5, 6, 7, 8, 0, new byte[0], 0, 200, 200))
                    .isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.READY);
            assertThat(view.ticket()).isNotEqualTo(oldTicket);
            assertThat(view.length()).isZero();
            assertThat(queue.acknowledge(oldTicket)).isFalse();
            assertThat(queue.pending()).isEqualTo(1);
        }
        try (var queue = BoundedPayloadQueue.open(file, 1, 8)) {
            var view = new BoundedPayloadQueue.Buffer(8);
            assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.READY);
            assertThat(view.word(0)).isEqualTo(5);
            assertThat(queue.acknowledge(view.ticket())).isTrue();
            assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.EMPTY);
            assertThat(view.ticket()).isEqualTo(-1);
            assertThat(queue.rejected()).isEqualTo(1);
            assertThat(queue.lossFromMillis()).isEqualTo(90);
            assertThat(queue.lossToMillis()).isEqualTo(120);
        }
    }

    @Test
    void rejectionBranchesAreDistinctAndBoundsOnlyWiden() throws Exception {
        Path file = directory.resolve("queue");
        try (var queue = BoundedPayloadQueue.open(file, 1, 2)) {
            assertThat(queue.offer(0, 0, 0, 0, 0, new byte[3], 3, 10, 20))
                    .isEqualTo(BoundedPayloadQueue.Offer.OVERSIZED);
            assertThat(queue.offer(0, 0, 0, 0, -1, new byte[0], 0, 12, 18))
                    .isEqualTo(BoundedPayloadQueue.Offer.INVALID);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> queue.offer(0, 0, 0, 0, 0, new byte[0], 0, 5, 25))
                                .get(5, TimeUnit.SECONDS))
                        .isEqualTo(BoundedPayloadQueue.Offer.WRONG_PRODUCER);
            }
            assertThat(queue.pending()).isZero();
            assertThat(queue.rejected()).isEqualTo(3);
            for (var reason : new BoundedPayloadQueue.Offer[] {
                BoundedPayloadQueue.Offer.OVERSIZED,
                BoundedPayloadQueue.Offer.INVALID,
                BoundedPayloadQueue.Offer.WRONG_PRODUCER
            }) assertThat(queue.rejected(reason)).isEqualTo(1);
            assertThat(queue.lossFromMillis()).isEqualTo(5);
            assertThat(queue.lossToMillis()).isEqualTo(25);
            assertThatThrownBy(() -> BoundedPayloadQueue.open(file, 1, 2)).isInstanceOf(IOException.class);
            assertThat(queue.offer(1, 2, 3, 4, 0, new byte[] {9}, 1, 30, 30))
                    .isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
            assertThat(queue.peek(new BoundedPayloadQueue.Buffer(2))).isEqualTo(BoundedPayloadQueue.Read.READY);
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThat(executor.submit(() -> {
                                    try {
                                        queue.peek(new BoundedPayloadQueue.Buffer(2));
                                        return false;
                                    } catch (IllegalStateException expected) {
                                        return true;
                                    }
                                })
                                .get(5, TimeUnit.SECONDS))
                        .isTrue();
            }
            assertThat(queue.pending()).isEqualTo(1);
        }
    }

    @Test
    void existingInvalidFilesAreRefusedWithoutReinitializing() throws Exception {
        Path file = directory.resolve("queue");
        Files.write(file, new byte[0]);
        assertThatThrownBy(() -> BoundedPayloadQueue.open(file, 1, 2)).isInstanceOf(IOException.class);
        assertThat(Files.size(file)).isZero();
        Path valid = directory.resolve("valid");
        try (var queue = BoundedPayloadQueue.open(valid, 1, 2)) {
            assertThat(queue.pending()).isZero();
        }
        assertThatThrownBy(() -> BoundedPayloadQueue.open(valid, 2, 2)).isInstanceOf(IOException.class);
        try (var channel = java.nio.channels.FileChannel.open(valid, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {0, 0, 0, 0}), 0);
        }
        assertThatThrownBy(() -> BoundedPayloadQueue.open(valid, 1, 2)).isInstanceOf(IOException.class);
    }

    @Test
    void corruptCursorsAndPayloadLengthCannotBeAcknowledged() throws Exception {
        Path file = directory.resolve("queue");
        try (var queue = BoundedPayloadQueue.open(file, 1, 2)) {
            assertThat(queue.offer(1, 2, 3, 4, 0, new byte[] {9}, 1, 1, 2))
                    .isEqualTo(BoundedPayloadQueue.Offer.ACCEPTED);
        }
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(3);
            bytes.flip();
            channel.write(bytes, 4096 + 36);
        }
        try (var queue = BoundedPayloadQueue.open(file, 1, 2)) {
            assertThatThrownBy(() -> queue.peek(new BoundedPayloadQueue.Buffer(8)))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Malformed queued payload");
            assertThat(queue.acknowledge(0)).isFalse();
            assertThat(queue.pending()).isEqualTo(1);
        }
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(2);
            bytes.flip();
            channel.write(bytes, 256);
        }
        assertThatThrownBy(() -> BoundedPayloadQueue.open(file, 1, 2))
                .isInstanceOf(IOException.class)
                .hasMessage("Corrupt payload queue cursors");
    }

    @Test
    void ticketExhaustionDoesNotWrapIntoOldAcknowledgements() throws Exception {
        Path file = directory.resolve("queue");
        try (var queue = BoundedPayloadQueue.open(file, 1, 2)) {
            assertThat(queue.pending()).isZero();
        }
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            for (long offset : new long[] {128, 256}) {
                var bytes =
                        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(Long.MAX_VALUE);
                bytes.flip();
                channel.write(bytes, offset);
            }
        }
        try (var queue = BoundedPayloadQueue.open(file, 1, 2)) {
            assertThat(queue.offer(0, 0, 0, 0, 0, new byte[0], 0, 1, 2)).isEqualTo(BoundedPayloadQueue.Offer.EXHAUSTED);
            assertThat(queue.rejected(BoundedPayloadQueue.Offer.EXHAUSTED)).isEqualTo(1);
            assertThat(queue.pending()).isZero();
            assertThat(queue.acknowledge(Long.MAX_VALUE)).isFalse();
        }
    }

    @Test
    void separateProducerAndConsumerNeverObserveTornOrReorderedPayloads() throws Exception {
        try (var queue = BoundedPayloadQueue.open(directory.resolve("queue"), 8, 8);
                var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> {
                byte[] bytes = new byte[8];
                for (int i = 0; i < 4096; i++) {
                    java.util.Arrays.fill(bytes, (byte) i);
                    BoundedPayloadQueue.Offer result;
                    do {
                        if (Thread.currentThread().isInterrupted())
                            throw new IllegalStateException("Interrupted producer");
                        result = queue.offer(i, ~i, i * 2L, i * 3L, i, bytes, 8, i, i);
                    } while (result == BoundedPayloadQueue.Offer.FULL);
                    if (result != BoundedPayloadQueue.Offer.ACCEPTED)
                        throw new IllegalStateException("Wrong offer branch");
                }
            });
            try {
                var view = new BoundedPayloadQueue.Buffer(8);
                int read = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (read < 4096 && System.nanoTime() < deadline) {
                    var branch = queue.peek(view);
                    if (branch == BoundedPayloadQueue.Read.EMPTY) continue;
                    assertThat(branch).isEqualTo(BoundedPayloadQueue.Read.READY);
                    assertThat(view.word(0)).isEqualTo(read);
                    assertThat(view.word(1)).isEqualTo(~read);
                    assertThat(view.word(2)).isEqualTo(read * 2L);
                    assertThat(view.word(3)).isEqualTo(read * 3L);
                    assertThat(view.version()).isEqualTo(read);
                    assertThat(view.length()).isEqualTo(8);
                    for (byte b : view.payload()) assertThat(b).isEqualTo((byte) read);
                    assertThat(queue.acknowledge(view.ticket())).isTrue();
                    read++;
                }
                assertThat(read).isEqualTo(4096);
                writer.get(5, TimeUnit.SECONDS);
                assertThat(queue.pending()).isZero();
            } finally {
                writer.cancel(true);
            }
        }
    }

    @Test
    void actualProcessKillPreservesPublishedEntryAndOverflowBounds() throws Exception {
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("trace.testClasspath"),
                        PayloadQueueCrashWorker.class.getName(),
                        directory.toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("worker.log").toFile())
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(directory.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertThat(Files.exists(directory.resolve("ready")))
                    .as(Files.readString(directory.resolve("worker.log")))
                    .isTrue();
            assertThat(Files.readString(directory.resolve("ready"))).isEqualTo("accepted-and-rejected-without-close");
            child.destroyForcibly();
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();
            for (int reopen = 0; reopen < 2; reopen++) {
                try (var queue = BoundedPayloadQueue.open(directory.resolve("queue"), 1, 8)) {
                    var view = new BoundedPayloadQueue.Buffer(8);
                    assertThat(queue.peek(view)).isEqualTo(BoundedPayloadQueue.Read.READY);
                    assertThat(view.word(0)).isEqualTo(11);
                    assertThat(view.word(1)).isEqualTo(22);
                    assertThat(view.word(2)).isEqualTo(33);
                    assertThat(view.word(3)).isEqualTo(44);
                    assertThat(view.version()).isEqualTo(7);
                    assertThat(view.length()).isEqualTo(3);
                    assertThat(view.payload()).startsWith((byte) 1, (byte) 2, (byte) -1);
                    assertThat(queue.rejected(BoundedPayloadQueue.Offer.FULL)).isEqualTo(1);
                    assertThat(queue.lossFromMillis()).isEqualTo(90);
                    assertThat(queue.lossToMillis()).isEqualTo(120);
                }
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(20, TimeUnit.SECONDS);
            }
        }
    }
}
