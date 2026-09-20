/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

import static org.assertj.core.api.Assertions.assertThat;

import in.gravaxis.trace.core.testing.Properties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chunk key and the range decomposition decide what a rollback reads.
 *
 * <p>The property that matters is coverage: if a chunk the query asks for falls outside every range,
 * the rollback silently misses part of the world. Tightness is a performance question and is
 * measured, not asserted.
 */
class MortonPropertyTest {

    @Test
    @DisplayName("a chunk key round-trips across the whole addressable world")
    void keyRoundTrips() {
        Properties.forAll("morton round-trip", gen -> {
            int chunkX = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
            int chunkZ = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);

            long key = Morton.key(chunkX, chunkZ);

            assertThat(key).as("key fits in 44 bits").isBetween(0L, (1L << 44) - 1);
            assertThat(Morton.chunkX(key)).as("x").isEqualTo(chunkX);
            assertThat(Morton.chunkZ(key)).as("z").isEqualTo(chunkZ);
        });
    }

    @Test
    @DisplayName("distinct chunks get distinct keys")
    void keysAreInjective() {
        Properties.forAll("morton injective", gen -> {
            int ax = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
            int az = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
            int bx = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
            int bz = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);

            boolean samePosition = ax == bx && az == bz;
            boolean sameKey = Morton.key(ax, az) == Morton.key(bx, bz);

            assertThat(sameKey).as("(%d,%d) vs (%d,%d)", ax, az, bx, bz).isEqualTo(samePosition);
        });
    }

    @Test
    @DisplayName("the decomposition covers every chunk of the rectangle it was given")
    void decompositionCoversTheRectangle() {
        Properties.forAll("morton cover", 300, gen -> {
            int minX = gen.ints(-40, 40);
            int minZ = gen.ints(-40, 40);
            ChunkRect rect = new ChunkRect(minX, minZ, minX + gen.ints(0, 20), minZ + gen.ints(0, 20));
            int maxRanges = gen.ints(1, 64);

            List<MortonRange> ranges = MortonRanges.decompose(rect, maxRanges, 0);

            assertThat(ranges).isNotEmpty();
            for (int x = rect.minChunkX(); x <= rect.maxChunkX(); x++) {
                for (int z = rect.minChunkZ(); z <= rect.maxChunkZ(); z++) {
                    long key = Morton.key(x, z);
                    assertThat(ranges.stream().anyMatch(range -> range.contains(key)))
                            .as("chunk (%d,%d) key %d is covered by %s", x, z, key, ranges)
                            .isTrue();
                }
            }
        });
    }

    @Test
    @DisplayName("a single chunk decomposes to exactly one key")
    void singleChunkIsOneKey() {
        Properties.forAll("morton single chunk", gen -> {
            int chunkX = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
            int chunkZ = gen.edgy(Morton.MIN_CHUNK, Morton.MAX_CHUNK);

            List<MortonRange> ranges = MortonRanges.decompose(ChunkRect.of(chunkX, chunkZ));

            assertThat(ranges).hasSize(1);
            assertThat(ranges.get(0).low()).isEqualTo(Morton.key(chunkX, chunkZ));
            assertThat(ranges.get(0).high()).isEqualTo(Morton.key(chunkX, chunkZ));
            assertThat(ranges.get(0).coarse()).isFalse();
        });
    }

    @Test
    @DisplayName("a long thin region stays within the range budget, at the cost of coarseness")
    void tunnelStaysWithinBudget() {
        // The case Z-order is worst at, and the one SPIKE-4 exists for: a 500-block tunnel one
        // chunk wide. It must still be covered, and it must not explode into thousands of scans.
        ChunkRect tunnel = ChunkRect.ofBlocks(0, 0, 8000, 15);
        int maxRanges = 64;

        List<MortonRange> ranges = MortonRanges.decompose(tunnel, maxRanges, MortonRanges.DEFAULT_MERGE_GAP);

        assertThat(ranges.size()).isLessThanOrEqualTo(maxRanges);
        for (int x = tunnel.minChunkX(); x <= tunnel.maxChunkX(); x++) {
            long key = Morton.key(x, 0);
            assertThat(ranges.stream().anyMatch(range -> range.contains(key)))
                    .as("chunk (%d,0) is covered", x)
                    .isTrue();
        }
        long scanned = ranges.stream().mapToLong(MortonRange::size).sum();
        // Reported, not asserted as a quality bar: this is the number SPIKE-4 would improve, and
        // pretending to know the right value for it would be inventing a measurement.
        assertThat(scanned).isPositive();
    }

    @Test
    @DisplayName("an empty or out-of-world rectangle is rejected rather than silently clamped")
    void invalidRectanglesAreRejected() {
        assertThat(java.util.stream.Stream.of(
                        catching(() -> new ChunkRect(5, 0, 4, 0)),
                        catching(() -> new ChunkRect(0, 5, 0, 4)),
                        catching(() -> new ChunkRect(Morton.MIN_CHUNK - 1, 0, 0, 0))))
                .allMatch(t -> t instanceof IllegalArgumentException);
    }

    private static Throwable catching(Runnable runnable) {
        try {
            runnable.run();
            return new AssertionError("expected a rejection");
        } catch (Throwable t) {
            return t;
        }
    }
}
