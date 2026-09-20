/*
 * Trace - block logging and rollback for Paper servers
 * Copyright (C) 2026 Gravaxis
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
 */

package in.gravaxis.trace.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a scenario reports back to the build: assertions, facts, and measurements.
 *
 * <p>Deliberately hand-rolled JSON. The harness must not drag a serialization library onto a test
 * server, and exactly one Gradle task reads the format.
 */
public final class HarnessResult {

    /** Outcome of a scenario run. */
    public enum Status {
        /** Every assertion held. */
        PASS,
        /** At least one assertion failed; {@code failures} says which. */
        FAIL
    }

    /**
     * One measurement.
     *
     * @param name dotted metric name, for example {@code tick.p99}
     * @param value the measured value
     * @param unit {@code ms}, {@code bytes}, {@code count}, or null for a bare number
     */
    public record Metric(String name, double value, @Nullable String unit) {}

    private final String scenario;
    private final Map<String, String> params;
    private final Map<String, String> details = new LinkedHashMap<>();
    private final List<Metric> metrics = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    public HarnessResult(String scenario, Map<String, String> params) {
        this.scenario = scenario;
        this.params = new LinkedHashMap<>(params);
    }

    /** Records a fact about the run. Facts are reported whether the scenario passes or fails. */
    public HarnessResult detail(String key, Object value) {
        details.put(key, String.valueOf(value));
        return this;
    }

    /** Records a measurement. Only measurements recorded here can ever reach the README. */
    public HarnessResult metric(String name, double value, @Nullable String unit) {
        metrics.add(new Metric(name, value, unit));
        return this;
    }

    /** Records a failed assertion. Any failure makes the whole scenario fail. */
    public HarnessResult failure(String message) {
        failures.add(message);
        return this;
    }

    /**
     * Asserts a condition, recording {@code message} when it does not hold.
     *
     * @return this, so assertions chain
     */
    public HarnessResult require(boolean condition, String message) {
        if (!condition) {
            failure(message);
        }
        return this;
    }

    public Status status() {
        return failures.isEmpty() ? Status.PASS : Status.FAIL;
    }

    public String scenario() {
        return scenario;
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    /** The result as JSON, which is what the Gradle tasks parse. */
    public String toJson() {
        StringBuilder out = new StringBuilder(512);
        out.append("{\n  \"scenario\": ").append(quote(scenario));
        out.append(",\n  \"status\": ").append(quote(status().name()));
        appendStringMap(out, "params", params);
        appendStringMap(out, "details", details);
        out.append(",\n  \"metrics\": [");
        for (int i = 0; i < metrics.size(); i++) {
            Metric metric = metrics.get(i);
            out.append(i == 0 ? "\n    " : ",\n    ");
            out.append("{\"name\": ")
                    .append(quote(metric.name()))
                    .append(", \"value\": ")
                    .append(number(metric.value()))
                    .append(", \"unit\": ")
                    .append(metric.unit() == null ? "null" : quote(metric.unit()))
                    .append('}');
        }
        out.append(metrics.isEmpty() ? "]" : "\n  ]");
        out.append(",\n  \"failures\": [");
        for (int i = 0; i < failures.size(); i++) {
            out.append(i == 0 ? "\n    " : ",\n    ").append(quote(failures.get(i)));
        }
        out.append(failures.isEmpty() ? "]" : "\n  ]");
        out.append("\n}\n");
        return out.toString();
    }

    private static void appendStringMap(StringBuilder out, String name, Map<String, String> map) {
        out.append(",\n  ").append(quote(name)).append(": {");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            out.append(first ? "\n    " : ",\n    ");
            first = false;
            out.append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
        }
        out.append(map.isEmpty() ? "}" : "\n  }");
    }

    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
