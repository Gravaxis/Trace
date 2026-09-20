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

## What it measures now (2026-09-20)

`AllocationProbe` is gone. Both instruments drive the real `CaptureService` through the same two
entry points the server's listener calls, `captureBlockChange` and `confirmStaged`. To make that
possible the class moved from `trace-paper` to `trace-core`, which cost nothing because it never had
a Bukkit type on it: the world is reached through a `StateReader`.

That was a move rather than an extraction on purpose. A platform-free delegate that `CaptureService`
called would leave two hot paths in the tree — the one the gate measures and the one the listener
runs — and an allocation added to the wrapper would be invisible to a green gate. The gated bytecode
and the called bytecode are now the same bytecode.

Two paths are measured, because the class has exactly two and no third:

* `captureRejectedAtTickEnd` — a tick of events that turn out to have changed nothing, staged and
  then rejected at tick end. This is the ADR-0014 path.
* `capturePublishedToRing` — a tick of real changes: staged, stamped by the slot clock, packed into
  four longs and written into the memory-mapped ring.

There is no way to stage without then confirming or flushing, so "staging alone" is not a measurable
path and is not pretended to be one.

**What is still not measured**, and must not be read as measured: Bukkit's event dispatch, the
dictionary lookups that turn a `Material` and a `UUID` into ids, and the world read inside the
tick-end handler. All three live in `trace-paper` and need a server. Gate P1 covers the encoder, not
the listener around it.

**Every measurement asserts which branch it took.** A reading of zero means nothing on its own: a
position rejected as out of range, and a record dropped into a full ring, allocate nothing either.
The tests therefore assert the counters as well as the bytes — every record rejected, or every
record published with none dropped and none out of range. For the same reason the drop counter was
split into slot-overflow and ring-full, which ADR-0012 had already asked for: one merged counter
cannot tell a test which branch it exercised.

One deliberate difference from production: the harness raises the slot clock's drift bound. A
benchmark publishes far faster than any server, and at the production bound of two milliseconds
almost every record would take the drop branch, so the benchmark would measure dropping while
claiming to measure publishing. Nothing else about the path changes, and the tests assert that
nothing was dropped.

**Result, 2026-09-20, on the machine named in the results directory: zero bytes**, for both paths,
under the normal JIT and again with escape analysis and C2 disabled. Not "below a threshold" —
zero, counted in whole bytes by `ThreadMXBean`.

## Alternatives considered

* **Keep `== 0.0` and let the build fail.** Rejected: it fails on correct code, so it would be
  disabled within a week, which is worse than an honest threshold.
* **Drop JMH and keep only the exact test.** Rejected: JMH also measures throughput and gives the
  per-operation figure that regression-tracking needs.
* **Compare against the empty baseline each run.** Rejected as the primary rule: it makes the gate's
  strictness depend on a noisy number. The fixed threshold plus the exact test is easier to reason
  about, and the baseline is still measured and recorded every run.
