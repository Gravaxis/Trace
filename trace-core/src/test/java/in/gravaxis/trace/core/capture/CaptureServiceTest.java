/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.capture;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.record.EventRecords;
import in.gravaxis.trace.core.ring.MappedEventRing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What capture promises, without a server.
 *
 * <p>The headline is ADR-0014: an event that fired while the world stayed the same is not history.
 * That property was only covered by a full server scenario before, which is a slow and indirect way
 * to test a decision this central.
 */
class CaptureServiceTest {
    @Test
    void excessThreadsNeverShareAnSpscRingAndLossBoundsSurviveReopen() throws Exception {
        int threads = CaptureService.MAX_SLOTS + 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int x = i;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                capture.captureBlockChange(WORLD, x, Y, 0, STONE, ACTOR, CAUSE, KIND);
                capture.confirmStaged((w, bx, by, bz) -> AIR);
            });
            workers.add(worker);
            worker.start();
        }
        long before = System.currentTimeMillis();
        start.countDown();
        for (Thread worker : workers) worker.join();
        assertThat(capture.rings()).hasSize(CaptureService.MAX_SLOTS);
        assertThat(capture.slotsExhausted()).isEqualTo(8);
        assertThat(capture.droppedNoSlot()).isEqualTo(8);
        assertThat(capture.published()).isEqualTo(CaptureService.MAX_SLOTS);
        assertThat(drainAll()).hasSize(CaptureService.MAX_SLOTS);
        try (var losses = CaptureLoss.open(directory.resolve("capture.loss"))) {
            assertThat(losses.count()).isEqualTo(8);
            assertThat(losses.fromMillis()).isLessThanOrEqualTo(before);
            assertThat(losses.toMillis()).isGreaterThanOrEqualTo(before);
        }
    }

    private static final int WORLD = 1;
    private static final int STONE = 11;
    private static final int AIR = 0;
    private static final int ACTOR = 7;
    private static final int CAUSE = 1;
    private static final int KIND = 1;
    private static final int Y = 64;

    @TempDir
    Path directory;

    private CaptureService capture;

    @BeforeEach
    void open() {
        capture = new CaptureService(directory, 1 << 12);
    }

    @AfterEach
    void close() {
        capture.close();
    }

    @Test
    @DisplayName("an event that changed nothing is not recorded")
    void rejectsEventsThatChangedNothing() {
        // Five break events at one block, and the block is still there afterwards. This is the
        // reported lava-punch case: a client mod fires the event, the block never breaks.
        for (int i = 0; i < 5; i++) {
            capture.captureBlockChange(WORLD, 3, Y, 4, STONE, ACTOR, CAUSE, KIND);
        }
        capture.confirmStaged((world, x, y, z) -> STONE);

        assertThat(capture.captured()).isEqualTo(5);
        assertThat(capture.rejectedUnchanged()).isEqualTo(5);
        assertThat(capture.published()).isZero();
        assertThat(drainAll()).isEmpty();
    }

    @Test
    @DisplayName("an event that changed the world is recorded, with the state the world really took")
    void publishesEventsThatChanged() {
        capture.captureBlockChange(WORLD, 3, Y, 4, STONE, ACTOR, CAUSE, KIND);
        capture.confirmStaged((world, x, y, z) -> AIR);

        assertThat(capture.published()).isEqualTo(1);
        assertThat(capture.rejectedUnchanged()).isZero();

        List<long[]> records = drainAll();
        assertThat(records).hasSize(1);
        long[] record = records.getFirst();
        assertThat(EventRecords.x(record[0])).isEqualTo(3);
        assertThat(EventRecords.y(record[0])).isEqualTo(Y);
        assertThat(EventRecords.z(record[0])).isEqualTo(4);
        assertThat(EventRecords.beforeState(record[1])).isEqualTo(STONE);
        assertThat(EventRecords.afterState(record[1]))
                .as("the after-state is what the world became, not what the event implied")
                .isEqualTo(AIR);
    }

    @Test
    @DisplayName("a position the record cannot address is counted and never reaches the encoder")
    void countsPositionsOutsideTheRange() {
        capture.captureBlockChange(WORLD, 0, EventRecords.MAX_Y + 1, 0, STONE, ACTOR, CAUSE, KIND);
        capture.confirmStaged((world, x, y, z) -> AIR);

        assertThat(capture.outOfRange()).isEqualTo(1);
        assertThat(capture.published()).isZero();
        assertThat(drainAll()).isEmpty();
    }

    @Test
    @DisplayName("a burst the slot clock cannot order is dropped, and counted as that and not as a full ring")
    void dropsWhatTheClockCannotOrder() {
        // The slot clock gives out a bounded number of ordered stamps per millisecond; past that it
        // refuses rather than misdate a record. A machine publishes faster than that bound, so this
        // burst reaches it. The ring is large enough that a full ring cannot be the explanation —
        // which is the point: one merged counter could not tell the two apart, and a test that
        // cannot name which branch it took is not evidence of anything.
        int burst = 200_000;
        for (int i = 0; i < burst; i++) {
            capture.captureBlockChange(WORLD, i & 0xFFFF, Y, (i >>> 16) & 0xFFFF, STONE, ACTOR, CAUSE, KIND);
            if (capture.captured() % CaptureService.STAGING_CAPACITY == 0) {
                capture.confirmStaged((world, x, y, z) -> AIR);
                ring().discardPending();
            }
        }
        capture.confirmStaged((world, x, y, z) -> AIR);

        assertThat(capture.droppedSlotOverflow())
                .as("a burst this fast must exhaust the clock's ordering budget")
                .isPositive();
        assertThat(capture.droppedRingFull())
                .as("the ring was drained every staging window, so it cannot be the cause")
                .isZero();
        assertThat(capture.dropped()).isEqualTo(capture.droppedSlotOverflow());
        assertThat(capture.published() + capture.dropped()).isEqualTo(burst);
    }

    @Test
    @DisplayName("staging more than a tick can hold publishes the rest unconfirmed rather than losing it")
    void publishesUnconfirmedWhenStagingIsFull() {
        int overflow = CaptureService.STAGING_CAPACITY + 10;
        for (int i = 0; i < overflow; i++) {
            capture.captureBlockChange(WORLD, i & 0xFFFF, Y, 0, STONE, ACTOR, CAUSE, KIND);
        }

        assertThat(capture.unconfirmed())
                .as("an unconfirmed record costs a skipped position; a lost one costs history")
                .isEqualTo(10);
        assertThat(capture.published()).isEqualTo(10);
    }

    private MappedEventRing ring() {
        List<MappedEventRing> rings = capture.rings();
        assertThat(rings).hasSize(1);
        return rings.getFirst();
    }

    private List<long[]> drainAll() {
        List<long[]> records = new ArrayList<>();
        for (MappedEventRing ring : capture.rings()) {
            ring.drain(
                    Integer.MAX_VALUE,
                    (position, states, actorTime, metadata) ->
                            records.add(new long[] {position, states, actorTime, metadata}));
        }
        return records;
    }
}
