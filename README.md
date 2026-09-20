# Trace

Block, container and entity logging with rollback for Paper and Folia servers.

> **Status: pre-release, under construction.** Nothing here is usable on a live server yet. The
> repository currently contains the build skeleton (milestone M0). Follow
> [docs/decisions](docs/decisions) for the design record and
> [ROADMAP.md](ROADMAP.md) for what is being built next.

## Performance

Every number Trace publishes comes from the benchmark harness in this repository
([`benchmarks/`](benchmarks)), is produced by `./gradlew benchmark`, and is regenerated into the
table below by a script — never typed by hand. Raw results are committed under
`benchmarks/results/`, together with the hardware, JVM, heap size and server build they were
measured on.

<!-- bench:start -->
_No benchmark results have been recorded yet._
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

* [`trace-api`](trace-api) — Apache License 2.0, so any plugin can integrate without licence
  friction.
* Everything else — GNU GPL v3 or later, with the additional permission in
  [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) confirming that using the Apache-2.0 API does not
  make your plugin a derivative of the GPL implementation.

Contributions are accepted under the [Developer Certificate of Origin](DCO); see
[CONTRIBUTING.md](CONTRIBUTING.md).
