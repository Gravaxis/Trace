# Trace

Block, container and entity logging with rollback for Paper and Folia servers.

> **Status: pre-release, under construction.** Nothing here is usable on a live server yet. Block
> breaks and places are captured, stored and rolled back on both Paper and Folia (milestone M2), and
> nothing else is. Follow [docs/decisions](docs/decisions) for the design record and
> [ROADMAP.md](ROADMAP.md) for what is built, what is next, and what each gate does not prove.

## Performance

Every number Trace publishes comes from the benchmark harness in this repository
([`benchmarks/`](benchmarks)), is produced by `./gradlew benchmark`, and is regenerated into the
table below by a script, never typed by hand. Raw results are committed under
`benchmarks/results/`, together with the hardware, JVM, heap size and server build they were
measured on.

Two lines below are gates rather than measurements, and they are worth reading as such. The
allocation figures come from JMH's profiler, whose floor is near but not exactly zero even for code
that provably allocates nothing; the gate of record is a separate test that counts whole bytes per
thread and must read zero, run again with the optimiser's escape analysis disabled. The crash line
counts restarts after `SIGKILL`: for the two journal runs, "verified" means that every event the
killed server had written down and Trace did not keep was inside a recorded gap, and that at least
one run really did lose something, because a crash that loses nothing verifies nothing. See
[ADR-0009](docs/decisions/0009-allocation-gate.md) and
[ADR-0013](docs/decisions/0013-journal-and-recovery.md).

<!-- bench:start -->
| Scenario | Server | Parameters | blocks.perSecond | blocks.written | heap.collections | heap.peakAfterGc | tick.max | tick.p50 | tick.p99 | tick.samples | wall.elapsed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `block-churn` | Folia 26.2 | ticks=200, blocksPerTick=256 | 5070.478 per second | 51200 | 0 | not measured | 175.97 ms | 0.82 ms | 4.77 ms | 200 | 10097.67 ms |
| `block-churn` | Paper 26.2 | ticks=200, blocksPerTick=256 | 5119.646 per second | 51200 | 2 | 287.3 MiB | 188.42 ms | 0.65 ms | 4.72 ms | 200 | 10000.69 ms |

Allocation gate (JMH `gc.alloc.rate.norm`): `allocatingReference` 48.000 B/op, `emptyBaseline` 1.4e-05 B/op, `capturePublishedToRing` 0.002 B/op, `captureRejectedAtTickEnd` 0.001 B/op.

Crash injection: 2/2 restarts verified on paper 26.2 build 126 after SIGKILL at 2497, 1045 ms; 3/3 restarts verified on folia 26.2 build 7 after SIGKILL at 2973, 3024, 2868 ms; 3/3 restarts verified on paper 26.2 build 126 after SIGKILL at 2973, 3024, 2868 ms.

Measured on AMD64 Family 23 Model 113 Stepping 0, AuthenticAMD, 16 threads, Windows 10 10.0, Oracle Corporation 25.0.4+7-LTS-189, Paper 26.2 build 126. Raw results: [`benchmarks/results/2026-09-20-280beffda3cb`](benchmarks/results/2026-09-20-280beffda3cb).
<!-- bench:end -->

## Install

Not yet published. Build locally with `./gradlew build` (JDK 25 required); the plugin jar is
produced at `trace-paper/build/libs/`.

## Migrating from CoreProtect

Not yet implemented (milestone M9). The importer will be free and part of the open-source build.

## Documentation

The documentation site is not up yet. Until then, the design record lives in
[docs/decisions](docs/decisions) and the provenance of every externally-derived fact is recorded in
[docs/decisions/provenance.md](docs/decisions/provenance.md).

## Licence

* [`trace-api`](trace-api): Apache License 2.0, so any plugin can integrate without licence
  friction.
* Everything else: GNU GPL v3 or later, with the additional permission in
  [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) confirming that using the Apache-2.0 API does not
  make your plugin a derivative of the GPL implementation.

Contributions are accepted under the [Developer Certificate of Origin](DCO); see
[CONTRIBUTING.md](CONTRIBUTING.md).
