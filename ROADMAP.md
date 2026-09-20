# Roadmap

Milestones run in order. Each one has a definition of done that has to pass before the next starts,
and an unmet item is reported as unmet rather than carried quietly.

| # | Milestone | State |
|---|---|---|
| M0 | Skeleton: modules, build, licences, a plugin that loads on Paper and Folia | **done** |
| M1 | Benchmark and crash harness — built *before* any feature | next |
| M2 | Walking skeleton: one event type captured with zero allocation, journalled, sealed into a shard, queried back, rolled back | planned |
| M3 | Storage engine: sharding, sealing, compaction, manifest, dictionaries, blobs, retention, purge, verify, quarantine | planned |
| M4 | Full capture: every kind and cause, block entities, entities, containers, sessions | planned |
| M5 | Mass edits: WorldEdit and FAWE hooks, section-diff patches, densification | planned |
| M6 | Rollback engine complete: streaming, chunk batching, adaptive budget, preview, resume, undo, relight | planned |
| M7 | Product surface: the `/trace` command tree, inspector output, localisation | planned |
| M8 | The rollback builder dialog, with a chat fallback and the container snapshot viewer | planned |
| M9 | Migration: CoreProtect and LogBlock importers, the compatibility bridge | planned |
| M10 | Operations: disk protection, backup, metrics, audit log, erasure, rate limits | planned |
| M11 | Release: documentation site, Modrinth and Hangar listings, Maven Central, signed release | planned |

Post-1.0: the web log browser, PostgreSQL and ClickHouse tiers, further importers.

## What M0 delivered

* Gradle 9.7.1 build with a pinned toolchain, convention plugins, and a version catalogue where
  every version is literal.
* Six modules with their dependency rules enforced by `verifyModuleGraph`.
* A plugin that enables on **Paper 26.2 build 126** and **Folia 26.2 build 7** and registers its
  service; proven by `./gradlew smokeTest`, which boots both pinned servers and asserts it.
* `trace-api` publishing to Maven Local with sources and javadoc.
* Error Prone and NullAway failing the build on a null dereference; Spotless enforcing SPDX headers.
* The design record in [docs/decisions](docs/decisions), including the provenance of every external
  fact the build relies on.

## Open questions carried forward

Tracked in the ADRs rather than here, but the ones that shape upcoming work:

* **M1** — whether JMH's `gc.alloc.rate.norm` can report exactly `0.0` for a provably empty
  benchmark. If it cannot, the allocation gate's definition is a decision for the owner, not a
  quiet loosening.
* **M1** — how CI breaks a block with no player connected: an internal fake player, a protocol bot,
  or a synthetic event. Whichever wins, the limitation is documented with the result.
* **M2** — five critical design defects found by adversarial review of the walking-skeleton design
  (gap coverage after `kill -9`, the crash-window lower bound, the drop-to-gap fence before
  planning, journal frame integrity, and block-entity capture on region threads) are fixed in ADRs
  before the code is written.
* **M3** — SPIKE-2 (real bytes per event) needs a CoreProtect database. Without one, no
  storage-density number is published.
* **M9** — the CoreProtect compatibility bridge needs classes in the `net.coreprotect` package, and
  parts of that API's wire format are undocumented. Scope and legal review pending.
