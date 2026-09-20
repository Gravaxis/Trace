/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.geom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Turns a chunk rectangle into the set of key ranges a scan has to read.
 *
 * <p>The decomposition walks the implicit quadtree of the Morton curve: a node fully inside the
 * rectangle becomes one exact range, a node outside it is dropped, and a node that straddles the
 * edge is subdivided. Subdivision is capped, because a long thin region — a five-hundred-block
 * tunnel one chunk wide is the honest worst case for Z-order — would otherwise produce thousands of
 * tiny ranges and turn one scan into thousands of seeks. Past the cap the remaining straddling
 * nodes are emitted whole and marked coarse.
 *
 * <p>Correctness never depends on how tight the cover is: every row a scan returns is checked
 * against the real query afterwards. What the cover controls is how much is read, which is why the
 * cap is a tuning knob and the coarse flag is reported rather than hidden.
 *
 * <p>SPIKE-4 in the build spec asks whether Hilbert ordering tightens this enough to justify its
 * complexity. That measurement is not done; Z-order is the accepted first version, and this class
 * is where the answer would land.
 */
public final class MortonRanges {

    /** Default ceiling on the number of ranges a plan will scan. */
    public static final int DEFAULT_MAX_RANGES = 256;

    /** Ranges separated by no more than this many keys are merged into one scan. */
    public static final int DEFAULT_MERGE_GAP = 8;

    private MortonRanges() {}

    public static List<MortonRange> decompose(ChunkRect rect) {
        return decompose(rect, DEFAULT_MAX_RANGES, DEFAULT_MERGE_GAP);
    }

    /**
     * Covers {@code rect} with at most roughly {@code maxRanges} key ranges.
     *
     * @param rect the chunks the query is interested in
     * @param maxRanges ceiling on subdivision work; the result may be shorter after merging
     * @param mergeGap merge two ranges separated by at most this many keys
     * @return ranges in ascending key order, never empty, together covering every chunk in the rect
     */
    public static List<MortonRange> decompose(ChunkRect rect, int maxRanges, int mergeGap) {
        if (maxRanges < 1) {
            throw new IllegalArgumentException("maxRanges must be positive");
        }
        List<MortonRange> exact = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(rootCovering(rect));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            if (node.disjointFrom(rect)) {
                continue;
            }
            if (node.insideOf(rect) || node.level == 0) {
                exact.add(new MortonRange(node.lowKey(), node.highKey(), false));
                continue;
            }
            if (exact.size() + queue.size() + 4 > maxRanges) {
                // Out of budget: take this node whole rather than splitting further. The rows it
                // adds are dropped by the post-filter.
                exact.add(new MortonRange(node.lowKey(), node.highKey(), true));
                continue;
            }
            node.addChildrenTo(queue);
        }
        exact.sort((a, b) -> Long.compareUnsigned(a.low(), b.low()));
        return merge(exact, mergeGap);
    }

    private static List<MortonRange> merge(List<MortonRange> ranges, int mergeGap) {
        List<MortonRange> merged = new ArrayList<>(ranges.size());
        for (MortonRange range : ranges) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }
            MortonRange last = merged.get(merged.size() - 1);
            if (range.low() <= last.high() + 1 + mergeGap) {
                merged.set(
                        merged.size() - 1,
                        new MortonRange(
                                last.low(),
                                Math.max(last.high(), range.high()),
                                last.coarse() || range.coarse() || range.low() > last.high() + 1));
            } else {
                merged.add(range);
            }
        }
        return List.copyOf(merged);
    }

    /** The smallest aligned quadtree node that contains the whole rectangle. */
    private static Node rootCovering(ChunkRect rect) {
        int minX = rect.minChunkX() + Morton.BIAS;
        int minZ = rect.minChunkZ() + Morton.BIAS;
        int maxX = rect.maxChunkX() + Morton.BIAS;
        int maxZ = rect.maxChunkZ() + Morton.BIAS;
        int level = 0;
        while (level < Morton.AXIS_BITS
                && ((minX >>> level) != (maxX >>> level) || (minZ >>> level) != (maxZ >>> level))) {
            level++;
        }
        return new Node(level, (minX >>> level) << level, (minZ >>> level) << level);
    }

    /** A square of 2^level by 2^level chunks, aligned to its own size, in biased coordinates. */
    private record Node(int level, int baseX, int baseZ) {

        private int size() {
            return 1 << level;
        }

        boolean disjointFrom(ChunkRect rect) {
            int minX = baseX - Morton.BIAS;
            int minZ = baseZ - Morton.BIAS;
            int maxX = minX + size() - 1;
            int maxZ = minZ + size() - 1;
            return maxX < rect.minChunkX()
                    || minX > rect.maxChunkX()
                    || maxZ < rect.minChunkZ()
                    || minZ > rect.maxChunkZ();
        }

        boolean insideOf(ChunkRect rect) {
            int minX = baseX - Morton.BIAS;
            int minZ = baseZ - Morton.BIAS;
            int maxX = minX + size() - 1;
            int maxZ = minZ + size() - 1;
            return minX >= rect.minChunkX()
                    && maxX <= rect.maxChunkX()
                    && minZ >= rect.minChunkZ()
                    && maxZ <= rect.maxChunkZ();
        }

        long lowKey() {
            return Morton.key(baseX - Morton.BIAS, baseZ - Morton.BIAS);
        }

        long highKey() {
            // An aligned node occupies a contiguous key range of exactly 4^level keys.
            return lowKey() + (1L << (2 * level)) - 1;
        }

        void addChildrenTo(Deque<Node> queue) {
            int child = level - 1;
            int half = 1 << child;
            queue.add(new Node(child, baseX, baseZ));
            queue.add(new Node(child, baseX + half, baseZ));
            queue.add(new Node(child, baseX, baseZ + half));
            queue.add(new Node(child, baseX + half, baseZ + half));
        }
    }
}
