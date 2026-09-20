# ADR-0009: The zero-allocation gate is two instruments, and one of them has a floor

* **Status:** accepted for implementation, **pending owner ratification** — this is a measured
  deviation from a locked decision
* **Date:** 2026-09-20
* **Milestone:** M1

## Context

The build spec locks gate P1 as: *"`gc.alloc.rate.norm == 0.0 B/op` on the encode-and-offer path is
a CI-enforced JMH gate."* Before writing any capture path, M1 measured whether that literal figure
is achievable, using a benchmark that provably allocates nothing, one that has the shape the real
encoder will have, and one control that deliberately allocates.

Measured on this machine (AMD Ryzen 7 3700X, 16 threads, Windows 10, JDK 25.0.4, JMH 1.37, GC
profiler, 3 forks × 5 iterations; raw output in `benchmarks/results/`):

| Benchmark | `gc.alloc.rate.norm` |
|---|---|
| `allocatingReference` — control, allocates a 4-long array | **48.000037 B/op** |
| `emptyBaseline` — returns a counter, allocates nothing | **0.000005 B/op** |
| `encodeIntoPreallocatedBuffer` — four longs into a preallocated slot | **0.000022 B/op** |

Two things follow. The instrument works: the control reads 48 B/op, exactly the size of a 4-long
array with a 16-byte header. And the instrument has a floor: code that cannot allocate still reads
about 5×10⁻⁶ B/op, because JMH normalises the whole iteration's allocation — its own harness
included — over the operations in it. A literal `== 0.0` comparison therefore fails on code that is
already perfect, which would make the gate useless rather than strict.

## Decision

The gate is two instruments, and the exact one is the gate of record.

1. **Exact (authoritative).** `ExactAllocationTest` measures the same code through
   `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`, which counts whole bytes for
   one thread. It runs five windows of 200,000 operations and requires the quietest window to be
   **exactly 0 bytes**. A per-operation allocation appears in every window; a one-off JVM
   housekeeping allocation (a class loaded, a compilation side effect — 96 bytes was observed once
   across a million operations) appears in one, so this is strict about the property and immune to
   that noise. The suite runs twice: normally, and with `-XX:-DoEscapeAnalysis
   -XX:TieredStopAtLevel=1`, so a pass cannot depend on the optimiser scalar-replacing an
   allocation that would be real in a cold server.
2. **JMH (supporting).** The gate keeps JMH's `gc.alloc.rate.norm` with a threshold of
   **0.01 B/op** — around 2,000× the measured floor, and 1,600× *below* one 16-byte object per
   operation, the smallest allocation the JVM can make. A real allocation on the hot path cannot
   hide under it: even one 16-byte object per 1,600 operations trips the gate.
3. **The control is gated too.** `allocatingReference` must report at least 16 B/op. If the profiler
   ever stops measuring, the build fails instead of going quietly green.

## What the owner is being asked to ratify

The spec's literal `== 0.0` is not achievable with JMH's profiler, on any code, on this machine. The
options were: fail the build forever, drop the JMH half, or keep JMH with a floor-derived threshold
and add an exact instrument that genuinely reads zero. This ADR takes the third. The property the
spec wanted — *the hot path allocates nothing* — is still enforced exactly; what changed is which
instrument enforces it.

## Consequences

* Every hot-path change must keep both halves green, and the exact half is the one to believe.
* The measurement is machine-dependent: the floor may differ elsewhere. The control and the
  threshold's large margin mean that does not weaken the gate.
* When M2 lands the real encoder, `AllocationProbe` is replaced by a call into `trace-core`'s codec
  and both instruments follow it. If the listener path (interning, blacklist lookup) cannot reach
  zero, that is a design problem to report, not a threshold to raise.

## Alternatives considered

* **Keep `== 0.0` and let the build fail.** Rejected: it fails on correct code, so it would be
  disabled within a week, which is worse than an honest threshold.
* **Drop JMH and keep only the exact test.** Rejected: JMH also measures throughput and gives the
  per-operation figure that regression-tracking needs.
* **Compare against the empty baseline each run.** Rejected as the primary rule: it makes the gate's
  strictness depend on a noisy number. The fixed threshold plus the exact test is easier to reason
  about, and the baseline is still measured and recorded every run.
