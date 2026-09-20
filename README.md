# Trace

Block, container and entity logging with rollback for Paper and Folia servers.

> **Status: pre-release, under construction.** Nothing here is usable on a live server yet. The
> repository currently contains the build skeleton (milestone M0). Follow
> [docs/decisions](docs/decisions) for the design record and
> [ROADMAP.md](ROADMAP.md) for what is being built next.

## Performance

Every number Trace publishes comes from the benchmark harness in this repository
([`benchmarks/`](benchmarks)), is produced by `./gradlew benchmark`, and is regenerated into the
table below by a script, never typed by hand. Raw results are committed under
`benchmarks/results/`, together with the hardware, JVM, heap size and server build they were
measured on.

<!-- bench:start -->
| Scenario | Server | Parameters | blocks.perSecond | blocks.written | heap.collections | heap.peakAfterGc | tick.max | tick.p50 | tick.p99 | tick.samples | wall.elapsed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `block-churn` | Folia 26.2 | ticks=200, blocksPerTick=256 | 5024.581 per second | 51200 | 0 | not measured | 261.58 ms | 1.03 ms | 5.80 ms | 200 | 10189.91 ms |
| `block-churn` | Paper 26.2 | ticks=200, blocksPerTick=256 | 5124.090 per second | 51200 | 2 | 262.9 MiB | 286.13 ms | 1.83 ms | 9.83 ms | 200 | 9992.02 ms |

Allocation gate (JMH `gc.alloc.rate.norm`): `allocatingReference` 48.000 B/op, `emptyBaseline` 6.6e-06 B/op, `encodeIntoPreallocatedBuffer` 3.8e-05 B/op.

Crash injection: 2/2 restarts verified on paper 26.2 build 126 after SIGKILL at 2497, 1045 ms.

Measured on AMD64 Family 23 Model 113 Stepping 0, AuthenticAMD, 16 threads, Windows 10 10.0, Oracle Corporation 25.0.4+7-LTS-189, Paper 26.2 build 126. Raw results: [`benchmarks/results/2026-09-20-ecc3cd19c85d`](benchmarks/results/2026-09-20-ecc3cd19c85d).
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
