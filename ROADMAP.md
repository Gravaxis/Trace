# Roadmap

Milestones run in order. Each one has a definition of done that has to pass before the next starts,
and an unmet item is reported as unmet rather than carried quietly.

| # | Milestone | State |
|---|---|---|
| M0 | Skeleton: modules, build, licences, a plugin that loads on Paper and Folia | **done** |
| M1 | Benchmark and crash harness — built *before* any feature | **done** |
| M2 | Walking skeleton: one event type captured with zero allocation, journalled, sealed into a shard, queried back, rolled back | **partly done** |
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

## What M1 delivered

* `./gradlew benchmark` — JMH microbenchmarks, a block-churn scenario on both pinned platforms, and
  the crash rig — writing `benchmarks/results/<date>-<commit>/` with the machine, JVM, heap and
  server builds recorded alongside every number.
* The README's benchmark table generated from that directory, and `verifyReadmeTable` failing the
  build if anyone edits it by hand.
* Gate P1 in two forms: an exact per-thread byte count that must read zero (run again with escape
  analysis disabled), and JMH's profiler with a floor-derived threshold. The measurements, and the
  deviation from the spec's literal `== 0.0`, are in
  [ADR-0009](docs/decisions/0009-allocation-gate.md) — **awaiting owner ratification**.
* A crash rig that kills a server with SIGKILL after a seeded random delay, restarts it, and
  verifies what survived; it currently shoots at a forced append log, and points at the real journal
  in M2.
* Tick percentiles from `ServerTickEndEvent` and post-GC heap sampling, because TPS is clamped at 20
  and peak heap without a collection is not a measurement.

## What M2 has delivered, and what it has not

Two of the four definition-of-done items pass. The other two do not, and M3 does not start until
they do.

**Passing.**

* Block breaks and places are captured at `MONITOR`, staged per thread, and confirmed against the
  world at `ServerTickEndEvent` — so an event that changed nothing is never written
  ([ADR-0014](docs/decisions/0014-log-changes-not-attempts.md)). The reported CoreProtect
  lava-punch case is a scenario assertion: five break events at a block that does not break produce
  five rejections and no history.
* Capture → ring → journal → hot window → sealed shard → keyset scan → rollback runs end to end.
  `./gradlew :trace-test-harness:integrationPaper` and `:integrationFolia` both pass, with the same
  numbers on each: 46 events seen, 36 recorded, 10 rejected as no-ops, 36 scanned back, 35 applied,
  1 skipped because someone had changed that block afterwards, across 2 chunks.
* The Folia run genuinely spans two regions: the areas are 128 chunks apart, which is past the
  boundary measured by asking the server's own `isOwnedByCurrentRegion` (see `provenance.md`). A
  rollback's writes are themselves captured, so a rollback can be rolled back.
* Gate P1 is green.

**Not passing, and why it is listed here rather than quietly deferred.**

* **The allocation gate does not yet measure the capture path.** It measures a probe. Until it
  points at the real encoder, "zero allocation on the tick thread" is a design claim, not a measured
  one, and it is not written anywhere it could be read as measured.
* **The crash rig does not yet shoot at Trace's journal.** It kills a server that is appending to
  its own forced log and verifies that, which proves the rig works, not that Trace's recovery does.
  The M2 requirement — every event lost to `kill -9` falls inside a recorded gap — is therefore
  untested.
* **An interrupted rollback does not resume.** There is no operation record and no resume cursor
  yet; a rollback that is cut off leaves the world half-restored with nothing to continue from.

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
* **Positioning** — the owner has identified Oasis and supplied an analysis
  ([`docs/competitive/oasis.md`](docs/competitive/oasis.md)). Its performance figures are the
  competitor's own unverifiable claims and stay out of every public surface. Two owner decisions
  come with it: the README's URL, and whether the AGPL question it raises reopens ADR-0006 (default:
  no).
