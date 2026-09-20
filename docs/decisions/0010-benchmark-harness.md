# ADR-0010: The harness comes before the features, and every number carries its provenance

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M1

## Context

Trace's central competitive claim is that its numbers are reproducible. The competitor whose
architecture the brief takes seriously publishes benchmarks whose scripts and raw results are
explicitly kept out of its repository. Matching that with a prettier table would be worthless; the
only answer is a harness anyone can run.

The brief therefore puts the harness in M1, before any feature exists. That ordering is the point:
a harness written after the thing it measures tends to measure what that thing happens to do.

## Decision

1. **One command.** `./gradlew benchmark` runs the JMH microbenchmarks, the server scenarios on both
   pinned platforms, and the crash-injection rig, then writes
   `benchmarks/results/<date>-<commit>[-dirty]/`.
2. **Results are committed**, including `environment.json`: CPU, thread count, OS, JVM, Gradle, the
   pinned Paper and Folia builds, the commit, and whether the working tree was dirty. A result
   measured on an uncommitted tree says so in its own directory name.
3. **The README table is generated**, never typed. `benchmarkReport` rewrites it between markers;
   `verifyReadmeTable` regenerates it in memory from the newest committed results and fails if the
   README has drifted. CI runs the latter, so a hand-edited number cannot survive review.
4. **Only recorded measurements can be published.** A scenario reports through
   `HarnessResult#metric`, and only metrics that appear in a committed result file reach the table.
   There is no path from an estimate to the README.
5. **Constrained heaps, stated.** Benchmark scenarios run with `-Xmx1G`, and the heap appears in
   every result. Numbers measured with a 16 GB heap say nothing about the servers Trace is for.
6. **Scenarios are shared with the correctness tests.** The same `ServerScenarioTask` boots the same
   pinned, deterministic, offline server for the M0 boot check and for a benchmark, because a
   benchmark run on a differently-configured server is not measuring the same system.
7. **Absence is reported as absence.** When no garbage collection happens during a scenario, the
   result records "no collection occurred" rather than a peak of zero bytes, which would read as a
   result rather than the lack of one.

## The crash rig

`crashTest` runs a writing scenario, waits for it to report that it is genuinely writing, kills the
JVM with `destroyForcibly` (SIGKILL / TerminateProcess — no shutdown hooks, no flush) after a
**seeded** random delay, restarts into the same directory, and runs a verifying scenario. The seed
and every kill delay go into the report, so a failure replays exactly.

In M1 the target is a simple forced append log, not Trace's journal, which does not exist yet. What
that proves is that the rig can detect loss: the verifier fails on a sequence gap, a bad checksum or
an empty file, and tolerates only a torn trailing record, which is what an interrupted `write()`
legitimately leaves behind. When M2 lands the journal, the gates P9 and P10 point the same rig at
it.

## Consequences

* Every benchmark run costs a few minutes of real servers booting. That is the price of measuring
  the system rather than a mock.
* Results accumulate in the repository. They are small JSON files, and being able to diff a number
  against the machine it came from is the whole point.
* The measurements in this repository are from one developer machine (Windows, Ryzen 7 3700X).
  Until CI runs the harness on known hardware, published numbers must name the machine — which the
  generated table does automatically.
