/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.testing;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Runs a property over many generated cases, and shrinks the first failure it finds.
 *
 * <pre>{@code
 * Properties.forAll("morton round-trips", gen -> {
 *     int x = gen.ints(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
 *     int z = gen.ints(Morton.MIN_CHUNK, Morton.MAX_CHUNK);
 *     long key = Morton.key(x, z);
 *     assertThat(Morton.chunkX(key)).isEqualTo(x);
 * });
 * }</pre>
 *
 * <p>Case count and seed are controllable from the build:
 * {@code -Dtrace.property.cases=1000000 -Dtrace.property.seed=42}. The default is modest so that a
 * normal build stays fast; the transcode and cursor properties the build spec calls for at millions
 * of cases are run with the property raised, and CI does that on a schedule.
 *
 * <p>A failure message always carries the seed and the shrunk choice sequence, so any failure can be
 * reproduced exactly — which matters more here than clever generation, because the properties Trace
 * needs are about losing nothing, and a property test you cannot reproduce is an anecdote.
 */
public final class Properties {

    private static final String CASES_PROPERTY = "trace.property.cases";
    private static final String SEED_PROPERTY = "trace.property.seed";
    private static final int DEFAULT_CASES = 500;
    private static final int SHRINK_BUDGET = 2_000;

    private Properties() {}

    public static void forAll(String description, Consumer<Gen> property) {
        forAll(description, defaultCases(), property);
    }

    public static void forAll(String description, int cases, Consumer<Gen> property) {
        long seed = defaultSeed();
        SplittableRandom random = new SplittableRandom(seed);
        for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
            SplittableRandom caseRandom = random.split();
            Gen gen = new Gen(caseRandom, null);
            Throwable failure = runCase(gen, property);
            if (failure != null) {
                throw report(description, seed, caseIndex, gen.recording(), property, failure);
            }
        }
    }

    private static @Nullable Throwable runCase(Gen gen, Consumer<Gen> property) {
        try {
            property.accept(gen);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Simplifies the failing choice sequence, then builds a message that can be replayed. */
    private static AssertionError report(
            String description, long seed, int caseIndex, long[] recording, Consumer<Gen> property, Throwable failure) {
        long[] smallest = recording;
        Throwable smallestFailure = failure;
        int budget = SHRINK_BUDGET;

        boolean improved = true;
        while (improved && budget > 0) {
            improved = false;
            for (int i = 0; i < smallest.length && budget > 0; i++) {
                for (long candidateValue : new long[] {0L, smallest[i] / 2, smallest[i] - 1}) {
                    if (budget-- <= 0 || candidateValue == smallest[i] || candidateValue < 0) {
                        continue;
                    }
                    long[] candidate = smallest.clone();
                    candidate[i] = candidateValue;
                    Throwable stillFails = replay(candidate, property);
                    if (stillFails != null) {
                        smallest = candidate;
                        smallestFailure = stillFails;
                        improved = true;
                        break;
                    }
                }
            }
        }

        AssertionError error = new AssertionError(
                """
                Property failed: %s
                  case %d of seed %d
                  shrunk choices: %s
                  reproduce: -Dtrace.property.seed=%d (and see the choices above)
                  failure: %s""".formatted(description, caseIndex, seed, Arrays.toString(smallest), seed, smallestFailure));
        error.initCause(smallestFailure);
        return error;
    }

    private static @Nullable Throwable replay(long[] choices, Consumer<Gen> property) {
        return runCase(new Gen(new SplittableRandom(0L), choices), property);
    }

    private static int defaultCases() {
        String configured = System.getProperty(CASES_PROPERTY);
        return configured == null ? DEFAULT_CASES : Integer.parseInt(configured);
    }

    private static long defaultSeed() {
        String configured = System.getProperty(SEED_PROPERTY);
        // A fixed default: a suite whose seed changes every run is a suite that fails on someone
        // else's machine and passes on yours.
        return configured == null ? 20260920L : Long.parseLong(configured);
    }
}
