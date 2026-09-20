/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.ring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The transport under the capture path: ordering, back-pressure, and surviving the process. */
class MappedEventRingTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("records come out in the order they went in, unchanged")
    void preservesOrderAndContent() throws IOException {
        try (MappedEventRing ring = MappedEventRing.open(directory.resolve("r-00.ring"), 0, 64)) {
            for (int i = 0; i < 40; i++) {
                assertThat(ring.offer(i, i + 1, i + 2, i + 3)).isTrue();
            }

            List<long[]> drained = new ArrayList<>();
            int count = ring.drain(100, (a, b, c, d) -> drained.add(new long[] {a, b, c, d}));

            assertThat(count).isEqualTo(40);
            for (int i = 0; i < 40; i++) {
                assertThat(drained.get(i)).containsExactly(i, i + 1, i + 2, i + 3);
            }
            assertThat(ring.pending()).isZero();
        }
    }

    @Test
    @DisplayName("a full ring refuses rather than blocking the thread that is offering")
    void fullRingRefuses() throws IOException {
        try (MappedEventRing ring = MappedEventRing.open(directory.resolve("r-01.ring"), 1, 8)) {
            for (int i = 0; i < 8; i++) {
                assertThat(ring.offer(i, 0, 0, 0)).isTrue();
            }

            assertThat(ring.offer(99, 0, 0, 0))
                    .as("the ninth record has nowhere to go")
                    .isFalse();

            ring.drain(1, (a, b, c, d) -> {});
            assertThat(ring.offer(99, 0, 0, 0))
                    .as("draining one makes room for one")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("records published but not consumed survive the process that wrote them")
    void survivesTheProcess() throws IOException {
        Path file = directory.resolve("r-02.ring");
        try (MappedEventRing ring = MappedEventRing.open(file, 2, 32)) {
            for (int i = 0; i < 5; i++) {
                ring.offer(i, 0, 0, 0);
            }
            ring.drain(2, (a, b, c, d) -> {});
            ring.force();
        }

        // What the "crashed" process had published but not consumed is still in the file: this is
        // what makes a kill -9 cost nothing that reached the ring.
        try (MappedEventRing reopened = MappedEventRing.open(file, 2, 32)) {
            List<Long> pending = new ArrayList<>();
            int seen = reopened.replayPending((a, b, c, d) -> pending.add(a));

            assertThat(seen).isEqualTo(3);
            assertThat(pending).containsExactly(2L, 3L, 4L);
            assertThat(reopened.pending()).as("replaying does not consume").isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a producer thread and a consumer thread never tear a record")
    void producerAndConsumerAgree() throws Exception {
        int records = 200_000;
        try (MappedEventRing ring = MappedEventRing.open(directory.resolve("r-03.ring"), 3, 1024)) {
            CountDownLatch started = new CountDownLatch(1);
            List<String> failures = new ArrayList<>();

            Thread producer = new Thread(() -> {
                started.countDown();
                int published = 0;
                while (published < records) {
                    // The producer never waits on the consumer; it retries, which is what a tick
                    // thread would not do — a real one would spill and move on.
                    if (ring.offer(published, published * 2L, published * 3L, published * 4L)) {
                        published++;
                    } else {
                        Thread.onSpinWait();
                    }
                }
            });
            producer.setDaemon(true);
            producer.start();
            started.await();

            long[] expected = {0};
            int consumed = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (consumed < records && System.nanoTime() < deadline) {
                consumed += ring.drain(512, (a, b, c, d) -> {
                    if (a != expected[0] || b != a * 2 || c != a * 3 || d != a * 4) {
                        failures.add("torn or out-of-order record at " + expected[0] + ": " + a + "," + b + "," + c
                                + "," + d);
                    }
                    expected[0]++;
                });
            }
            producer.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(failures).isEmpty();
            assertThat(consumed).isEqualTo(records);
        }
    }
}
