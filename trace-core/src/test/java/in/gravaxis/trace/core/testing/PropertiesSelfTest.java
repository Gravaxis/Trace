/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.core.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the test harness.
 *
 * <p>Trace owns its property runner instead of taking a library (ADR-0004), and a harness that
 * quietly stops finding failures is worse than no harness: every property in the project would go
 * green. So the runner is held to the two things it promises — it finds a failure, and it shrinks
 * the case before reporting it.
 */
class PropertiesSelfTest {

    @Test
    @DisplayName("a property that always holds passes without drama")
    void passingPropertyPasses() {
        AtomicInteger runs = new AtomicInteger();
        assertThatCode(() -> Properties.forAll("always true", 50, gen -> {
                    int value = gen.ints(0, 1000);
                    runs.incrementAndGet();
                    assertThat(value).isBetween(0, 1000);
                }))
                .doesNotThrowAnyException();
        assertThat(runs).hasValue(50);
    }

    @Test
    @DisplayName("a failing property is reported with a shrunk, replayable case")
    void failingPropertyShrinks() {
        assertThatThrownBy(() -> Properties.forAll("values stay below 100", 500, gen -> {
                    int value = gen.ints(0, 10_000);
                    assertThat(value).isLessThan(100);
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("values stay below 100")
                .hasMessageContaining("shrunk choices")
                .hasMessageContaining("trace.property.seed")
                .satisfies(error -> {
                    // The shrunk case should be at or near the boundary, not the random value that
                    // happened to fail first.
                    String message = String.valueOf(error.getMessage());
                    long shrunk = Long.parseLong(message.substring(message.indexOf('[') + 1, message.indexOf(']'))
                            .trim());
                    assertThat(shrunk).as("shrunk to the boundary").isBetween(100L, 200L);
                });
    }

    @Test
    @DisplayName("the same seed produces the same cases")
    void runsAreReproducible() {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        Properties.forAll(
                "record values", 20, gen -> first.append(gen.ints(0, 1_000_000)).append(','));
        Properties.forAll(
                "record values",
                20,
                gen -> second.append(gen.ints(0, 1_000_000)).append(','));
        assertThat(first.toString()).isEqualTo(second.toString());
    }
}
