/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.testing.Properties;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Key uniqueness rests entirely on this class: if two events in one chunk and one millisecond can
 * get the same sequence, the sealed shard's primary key collides and the seal fails — or worse,
 * dedupe eats a real event.
 */
class SlotClockPropertyTest {

    @Test
    @DisplayName("a slot never issues the same stamp twice, whatever the wall clock does")
    void stampsAreUnique() {
        Properties.forAll("slot clock uniqueness", 200, gen -> {
            SlotClock clock = new SlotClock(gen.ints(0, SlotClock.MAX_SLOT), gen.longs(0, 4));
            Set<Long> seen = new HashSet<>();
            long now = gen.longs(0, 1_000_000);
            long previous = Long.MIN_VALUE;

            for (int i = 0; i < 4_000; i++) {
                // A wall clock that mostly stands still, sometimes ticks, and occasionally jumps
                // backwards the way a real one does under NTP.
                now += switch ((int) gen.choice(8)) {
                    case 0 -> 1;
                    case 1 -> gen.longs(1, 50);
                    case 2 -> -gen.longs(0, 3);
                    default -> 0;
                };
                long stamp = clock.next(now);
                if (stamp == SlotClock.OVERFLOW) {
                    continue;
                }
                assertThat(seen.add(stamp)).as("stamp %d repeated", stamp).isTrue();
                assertThat(stamp).as("stamps increase").isGreaterThan(previous);
                previous = stamp;
                assertThat(SlotClock.slotOf(SlotClock.sequenceOf(stamp)))
                        .as("the slot is recoverable from the sequence")
                        .isEqualTo(clock.slot());
            }
        });
    }

    @Test
    @DisplayName("the logical clock never drifts further ahead than it is allowed to")
    void driftIsBounded() {
        Properties.forAll("slot clock drift", 200, gen -> {
            long maxDrift = gen.longs(0, 3);
            SlotClock clock = new SlotClock(gen.ints(0, SlotClock.MAX_SLOT), maxDrift);
            long now = gen.longs(0, 1000);
            int overflows = 0;

            // Far more events in one millisecond than the counter can hold: the clock must refuse
            // rather than borrow indefinitely from the future.
            for (int i = 0; i < 20_000; i++) {
                long stamp = clock.next(now);
                if (stamp == SlotClock.OVERFLOW) {
                    overflows++;
                    continue;
                }
                assertThat(SlotClock.millisOf(stamp) - now)
                        .as("drift stays within the bound")
                        .isBetween(0L, maxDrift);
            }
            assertThat(overflows).as("the bound was actually reached").isPositive();
            assertThat(clock.driftMillis(now)).isLessThanOrEqualTo(maxDrift);
        });
    }

    @Test
    @DisplayName("two slots in the same millisecond never collide")
    void slotsDoNotCollide() {
        SlotClock a = new SlotClock(3, 2);
        SlotClock b = new SlotClock(17, 2);

        Set<Long> stamps = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(stamps.add(a.next(1234))).isTrue();
            assertThat(stamps.add(b.next(1234))).isTrue();
        }
    }

    @Test
    @DisplayName("a seeded clock never reissues a stamp from before a restart or a slot handover")
    void seedingPreventsGoingBackwards() {
        SlotClock clock = new SlotClock(1, 2);
        long before = clock.next(1000);

        SlotClock afterRestart = new SlotClock(1, 2);
        afterRestart.seed(SlotClock.millisOf(before));

        long after = afterRestart.next(1000);
        assertThat(after)
                .as("the stamp after a restart is beyond the one before it")
                .isGreaterThan(before);
    }
}
