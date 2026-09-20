/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/**
 * A generator backed by a recorded sequence of choices.
 *
 * <p>Every value a property asks for is drawn from {@code random} and recorded. When a property
 * fails, the recording can be replayed and simplified — zero a choice, halve it, drop the tail —
 * and the property re-run against the simplified recording. That is what makes shrinking work
 * without every generator having to know how to shrink its own type.
 *
 * <p>Deliberately small. See ADR-0004 for why Trace does not take a property-testing library.
 */
public final class Gen {

    private final SplittableRandom random;
    private final long @Nullable [] replay;
    private final List<Long> recorded = new ArrayList<>();
    private int index;

    Gen(SplittableRandom random, long @Nullable [] replay) {
        this.random = random;
        this.replay = replay;
    }

    /** A value in {@code [0, bound)}, recorded so the case can be replayed and shrunk. */
    public long choice(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long value;
        if (replay != null && index < replay.length) {
            value = Math.floorMod(replay[index], bound);
        } else {
            value = random.nextLong(bound);
        }
        index++;
        recorded.add(value);
        return value;
    }

    /** An int in {@code [min, max]}. */
    public int ints(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        return (int) (min + choice((long) max - min + 1));
    }

    /** A long in {@code [min, max]}. */
    public long longs(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException(min + " > " + max);
        }
        long span = max - min;
        if (span == Long.MAX_VALUE || span < 0) {
            return min + choice(Long.MAX_VALUE);
        }
        return min + choice(span + 1);
    }

    public boolean bools() {
        return choice(2) == 1;
    }

    /** One of {@code values}. */
    @SafeVarargs
    public final <T> T pick(T... values) {
        return values[(int) choice(values.length)];
    }

    /** One of {@code values}. */
    public <T> T pick(List<T> values) {
        return values.get((int) choice(values.size()));
    }

    /**
     * A value biased towards the edges of its range.
     *
     * <p>Off-by-one bugs live at the boundaries, and uniform sampling over a 26-bit coordinate space
     * essentially never lands on one.
     */
    public int edgy(int min, int max) {
        return switch ((int) choice(4)) {
            case 0 -> min;
            case 1 -> max;
            case 2 -> ints(min, Math.min(max, min + 16));
            default -> ints(min, max);
        };
    }

    long[] recording() {
        long[] values = new long[recorded.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = recorded.get(i);
        }
        return values;
    }
}
