/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.record;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.testing.Gen;
import in.gravaxis.trace.core.testing.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The record codec has to be exactly lossless. Everything downstream — the journal, the shard, the
 * rollback — reads what this packs, so a single truncated bit is a wrong world later.
 */
class EventRecordsPropertyTest {

    @Test
    @DisplayName("a position round-trips for every coordinate the format claims to support")
    void positionRoundTrips() {
        Properties.forAll("position round-trip", gen -> {
            int x = coordinate(gen);
            int z = coordinate(gen);
            int y = gen.edgy(EventRecords.MIN_Y, EventRecords.MAX_Y);

            long packed = EventRecords.packPosition(x, y, z);

            assertThat(EventRecords.x(packed)).as("x").isEqualTo(x);
            assertThat(EventRecords.y(packed)).as("y").isEqualTo(y);
            assertThat(EventRecords.z(packed)).as("z").isEqualTo(z);
        });
    }

    @Test
    @DisplayName("states and the auxiliary field round-trip")
    void statesRoundTrip() {
        Properties.forAll("state round-trip", gen -> {
            int before = gen.edgy(0, EventRecords.MAX_STATE_ID);
            int after = gen.edgy(0, EventRecords.MAX_STATE_ID);
            int aux = gen.edgy(0, EventRecords.MAX_AUX);

            long packed = EventRecords.packStates(before, after, aux);

            assertThat(EventRecords.beforeState(packed)).as("before").isEqualTo(before);
            assertThat(EventRecords.afterState(packed)).as("after").isEqualTo(after);
            assertThat(EventRecords.aux(packed)).as("aux").isEqualTo(aux);
        });
    }

    @Test
    @DisplayName("a whole record round-trips, including the actor id split across two words")
    void recordRoundTrips() {
        Properties.forAll("record round-trip", gen -> {
            int x = coordinate(gen);
            int z = coordinate(gen);
            int y = gen.edgy(EventRecords.MIN_Y, EventRecords.MAX_Y);
            int before = gen.edgy(0, EventRecords.MAX_STATE_ID);
            int after = gen.edgy(0, EventRecords.MAX_STATE_ID);
            int aux = gen.edgy(0, EventRecords.MAX_AUX);
            long millis = gen.longs(0, EventRecords.MAX_RELATIVE_MILLIS);
            int actor = (int) gen.longs(0, EventRecords.MAX_ACTOR_ID);
            int sequence = gen.edgy(0, EventRecords.MAX_SEQUENCE);
            int world = gen.edgy(0, EventRecords.MAX_WORLD_ID);
            Cause cause = gen.pick(Cause.values());
            RecordKind kind = gen.pick(RecordKind.values());
            int sidecar = gen.edgy(0, EventRecords.MAX_SIDECAR);

            long l0 = EventRecords.packPosition(x, y, z);
            long l1 = EventRecords.packStates(before, after, aux);
            long l2 = EventRecords.packActorTime(millis, actor);
            long l3 = EventRecords.packMetadata(sequence, world, cause.id(), kind.id(), actor, sidecar);

            assertThat(EventRecords.x(l0)).isEqualTo(x);
            assertThat(EventRecords.y(l0)).isEqualTo(y);
            assertThat(EventRecords.z(l0)).isEqualTo(z);
            assertThat(EventRecords.beforeState(l1)).isEqualTo(before);
            assertThat(EventRecords.afterState(l1)).isEqualTo(after);
            assertThat(EventRecords.aux(l1)).isEqualTo(aux);
            assertThat(EventRecords.relativeMillis(l2)).isEqualTo(millis);
            assertThat(EventRecords.actorId(l2, l3)).as("actor id").isEqualTo(actor);
            assertThat(EventRecords.sequence(l3)).isEqualTo(sequence);
            assertThat(EventRecords.worldId(l3)).isEqualTo(world);
            assertThat(Cause.byId(EventRecords.cause(l3))).isEqualTo(cause);
            assertThat(RecordKind.byId(EventRecords.kind(l3))).isEqualTo(kind);
            assertThat(EventRecords.sidecar(l3)).isEqualTo(sidecar);
        });
    }

    @Test
    @DisplayName("positions outside the format's range are rejected, never truncated")
    void outOfRangeIsRejected() {
        assertThat(EventRecords.positionInRange(0, 0, 0)).isTrue();
        assertThat(EventRecords.positionInRange(
                        EventRecords.MAX_HORIZONTAL, EventRecords.MAX_Y, EventRecords.MIN_HORIZONTAL))
                .isTrue();
        assertThat(EventRecords.positionInRange(EventRecords.MAX_HORIZONTAL + 1, 0, 0))
                .isFalse();
        assertThat(EventRecords.positionInRange(0, EventRecords.MAX_Y + 1, 0)).isFalse();
        assertThat(EventRecords.positionInRange(0, 0, EventRecords.MIN_HORIZONTAL - 1))
                .isFalse();
    }

    @Test
    @DisplayName("the format covers the whole vanilla world")
    void formatCoversTheWorld() {
        // The world border caps block coordinates at +-29,999,984 and datapacks can build from
        // -2032 to 2031. If a future game drop widens either, this test is where it shows up.
        assertThat(EventRecords.positionInRange(29_999_984, 2031, 29_999_984)).isTrue();
        assertThat(EventRecords.positionInRange(-29_999_984, -2032, -29_999_984))
                .isTrue();
    }

    private static int coordinate(Gen gen) {
        return gen.edgy(EventRecords.MIN_HORIZONTAL, EventRecords.MAX_HORIZONTAL);
    }
}
