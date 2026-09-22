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
    @DisplayName("staging exhaustion gaps the loss instead of inventing an unconfirmed row")
    void stagingExhaustionIsGappedAndDuplicatesStillFit() {
        int overflow = CaptureService.STAGING_CAPACITY + 10;
        for (int i = 0; i < overflow; i++) {
            capture.captureBlockChange(WORLD, i & 0xFFFF, Y, 0, STONE, ACTOR, CAUSE, KIND);
        }

        capture.captureBlockChange(WORLD, 0, Y, 0, STONE, ACTOR, CAUSE, KIND);
        assertThat(capture.coalesced()).isEqualTo(1);
        assertThat(capture.unconfirmed()).isEqualTo(10);
        assertThat(capture.droppedStagingFull()).isEqualTo(10);
        assertThat(capture.dropped()).isEqualTo(10);
        assertThat(capture.published()).isZero();
        capture.confirmStaged((w, x, y, z) -> STONE);
        assertThat(capture.rejectedUnchanged()).isEqualTo(CaptureService.STAGING_CAPACITY + 1);
        assertThat(drainAll()).isEmpty();
        assertThat(capture.lossCount()).isEqualTo(10);
    }

    @Test
    void repeatedPositionKeepsFirstBeforeAndFinalAfterAndResetsIndex() {
        for (int tick = 0; tick < 3; tick++) {
            capture.captureBlockChange(WORLD, 3, Y, 4, STONE, ACTOR, CAUSE, KIND);
            capture.captureBlockChange(WORLD, 3, Y, 4, 12, ACTOR, CAUSE, KIND);
            capture.confirmStaged((w, x, y, z) -> AIR);
            List<long[]> rows = drainAll();
            assertThat(rows).hasSize(1);
            long[] row = rows.getFirst();
            assertThat(EventRecords.beforeState(row[1])).isEqualTo(STONE);
            assertThat(EventRecords.afterState(row[1])).isEqualTo(AIR);
            assertThat(EventRecords.actorId(row[2], row[3])).isEqualTo(ACTOR);
            assertThat(EventRecords.cause(row[3])).isEqualTo(CAUSE);
        }
        assertThat(capture.captured()).isEqualTo(6);
        assertThat(capture.coalesced()).isEqualTo(3);
        assertThat(capture.published()).isEqualTo(3);
        assertThat(capture.dropped()).isZero();
    }

    @Test
    void roundTripToFirstStateRejectsEveryObservationEvenWithMixedAttribution() {
        capture.captureBlockChange(WORLD, 3, Y, 4, STONE, ACTOR, CAUSE, KIND);
        capture.captureBlockChange(WORLD, 3, Y, 4, AIR, ACTOR + 1, CAUSE + 1, KIND);
        capture.confirmStaged((w, x, y, z) -> STONE);
        assertThat(capture.coalesced()).isEqualTo(1);
        assertThat(capture.rejectedUnchanged()).isEqualTo(2);
        assertThat(capture.published()).isZero();
        assertThat(capture.dropped()).isZero();
        assertThat(drainAll()).isEmpty();
    }

    @Test
    void ambiguousChangedAttributionGapsAllObservations() throws Exception {
        long before = System.currentTimeMillis();
        for (int branch = 0; branch < 3; branch++) {
            capture.captureBlockChange(WORLD, branch, Y, 0, STONE, ACTOR, CAUSE, KIND);
            capture.captureBlockChange(
                    WORLD,
                    branch,
                    Y,
                    0,
                    STONE,
                    ACTOR + (branch == 0 ? 1 : 0),
                    CAUSE + (branch == 1 ? 1 : 0),
                    KIND + (branch == 2 ? 1 : 0));
        }
        capture.confirmStaged((w, x, y, z) -> AIR);
        assertThat(capture.coalesced()).isEqualTo(3);
        assertThat(capture.droppedAmbiguous()).isEqualTo(6);
        assertThat(capture.dropped()).isEqualTo(6);
        assertThat(capture.published()).isZero();
        assertThat(drainAll()).isEmpty();
        try (var losses = CaptureLoss.open(directory.resolve("capture.loss"))) {
            assertThat(losses.count()).isEqualTo(6);
            assertThat(losses.fromMillis()).isLessThanOrEqualTo(before);
            assertThat(losses.toMillis()).isGreaterThanOrEqualTo(before);
        }
    }

    @Test
    void unreadableAndExplicitFlushNeverPublishInventedRows() throws Exception {
        long before = System.currentTimeMillis();
        capture.captureBlockChange(WORLD, 1, Y, 0, STONE, ACTOR, CAUSE, KIND);
        capture.confirmStaged((w, x, y, z) -> CaptureService.UNKNOWN_STATE);
        capture.captureBlockChange(WORLD, 1, Y, 0, STONE, ACTOR, CAUSE, KIND);
        capture.flushStagedUnconfirmed();
        capture.confirmStaged((w, x, y, z) -> {
            throw new AssertionError("flush left staged data");
        });
        assertThat(capture.droppedUnreadable()).isEqualTo(1);
        assertThat(capture.droppedUnconfirmedFlush()).isEqualTo(1);
        assertThat(capture.unconfirmed()).isEqualTo(2);
        assertThat(capture.dropped()).isEqualTo(2);
        assertThat(capture.published()).isZero();
        assertThat(drainAll()).isEmpty();
        try (var losses = CaptureLoss.open(directory.resolve("capture.loss"))) {
            assertThat(losses.count()).isEqualTo(2);
            assertThat(losses.fromMillis()).isLessThanOrEqualTo(before);
            assertThat(losses.toMillis()).isGreaterThanOrEqualTo(before);
        }
    }

    @Test
    void packedFieldOverflowAndInvalidReaderStatesAreGappedBeforeEncoding() {
        int[][] invalid = {
            {-1, STONE, CAUSE, KIND}, {EventRecords.MAX_WORLD_ID + 1, STONE, CAUSE, KIND},
            {WORLD, -1, CAUSE, KIND}, {WORLD, EventRecords.MAX_STATE_ID + 1, CAUSE, KIND},
            {WORLD, STONE, -1, KIND}, {WORLD, STONE, 256, KIND},
            {WORLD, STONE, CAUSE, -1}, {WORLD, STONE, CAUSE, 16}
        };
        for (int[] fields : invalid)
            capture.captureBlockChange(fields[0], 1, Y, 0, fields[1], ACTOR, fields[2], fields[3]);
        assertThat(capture.rings()).isEmpty();
        for (int state : new int[] {-2, EventRecords.MAX_STATE_ID + 1}) {
            capture.captureBlockChange(WORLD, 1, Y, 0, STONE, ACTOR, CAUSE, KIND);
            capture.confirmStaged((w, x, y, z) -> state);
        }
        assertThat(capture.invalidFields()).isEqualTo(10);
        assertThat(capture.dropped()).isEqualTo(10);
        assertThat(capture.lossCount()).isEqualTo(10);
        assertThat(capture.published()).isZero();
        assertThat(drainAll()).isEmpty();
    }

    @Test
    void hashCollisionsAndIdenticalCoordinatesInDifferentWorldsRemainDistinct() {
        // Assert that probing really collided; distinct read counts then expose accidental merging.
        int count = 1000;
        for (int world = 0; world < 2; world++)
            for (int x = 0; x < count; x++) capture.captureBlockChange(world, x, Y, 0, STONE, ACTOR, CAUSE, KIND);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        capture.confirmStaged((w, x, y, z) -> {
            reads.incrementAndGet();
            return STONE;
        });
        assertThat(reads.get()).isEqualTo(count * 2);
        assertThat(capture.indexCollisions()).isPositive();
        assertThat(capture.coalesced()).isZero();
        assertThat(capture.rejectedUnchanged()).isEqualTo(count * 2);
        assertThat(capture.dropped()).isZero();
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
