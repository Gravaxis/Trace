# Roadmap

Milestones run in order. Each one has a definition of done that has to pass before the next starts,
and an unmet item is reported as unmet rather than carried quietly.

| # | Milestone | State |
|---|---|---|
| M0 | Skeleton: modules, build, licences, a plugin that loads on Paper and Folia | **done** |
| M1 | Benchmark and crash harness — built *before* any feature | **done** |
| M2 | Walking skeleton: one event type captured with zero allocation, journalled, sealed into a shard, queried back, rolled back | **done** |
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

## What M2 delivered

All four definition-of-done items pass. Each is followed by what it does **not** establish, because
a gate is only worth what it actually checks.

**Capture, storage and rollback, end to end, on both platforms.**
`./gradlew :trace-test-harness:integrationTest` boots Paper 26.2 build 126 and Folia 26.2 build 7 and
runs two scenarios on each. The numbers come out identical on both: 46 events seen, 36 recorded, 10
rejected as no-ops, 36 read back, 35 restored, 1 skipped because someone had changed that block
afterwards, across 2 chunks. Break and place are both covered, and Trace's own rollback writes are
recorded so a rollback can be rolled back.

The Folia run genuinely spans two regions. The areas are 128 chunks apart, which is past the
boundary measured by asking the server's own `isOwnedByCurrentRegion` where its region ended; an
earlier version used 40 chunks and tested one region twice while claiming two. Thread names cannot
answer that question, because Folia services regions from a pool.

*Not established:* the events are fired by the harness rather than by a connected client, so the
server's own break path is not exercised. That is stated in every result this scenario produces, and
a protocol-level client is the M4 answer.

**Gate P1, on the real encoder.**
Both instruments now drive the same `CaptureService` the listener calls, over the two paths it has:
a tick of events that changed nothing and are rejected at tick end, and a tick of real changes that
are stamped, packed and written to the ring. The result is zero bytes, counted in whole bytes,
under the normal JIT and again with escape analysis and C2 disabled. Every measurement asserts which
branch it took, because a rejected position and a dropped record allocate nothing either.

*Not established:* Bukkit's event dispatch, the dictionary lookups and the world read in the
tick-end handler are not measured. Gate P1 covers the encoder, not the listener around it.
[ADR-0009](docs/decisions/0009-allocation-gate.md) says so in the same words.

**Every event lost to the crash the gate injects is covered by a gap.**
`./gradlew :trace-test-harness:crashJournalTest` kills a server with SIGKILL while Trace is
capturing, restarts it, and checks that every event the previous run wrote down and Trace does not
have falls inside a recorded gap. Ground truth is written per event and before the event, and every
event gets a position of its own so a loss cannot be masked by a later event at the same place.

A run that lost nothing verifies nothing, so the rig fails a set of iterations in which no iteration
ever lost an event. Each committed run records, per iteration, the kill delay, how many events the
killed server had written down, how many Trace did not have, and whether the iteration was lossy at
all: see `crash/` inside the newest directory under `benchmarks/results/`. Those files are the only
place a figure about what a crash costs may be read from, and everything in this paragraph is read
from them.

In the committed run, kills at roughly three seconds lost 438, 1,183 and 554 events of about 47,000
recorded on Paper, and 874, none and 695 of about 72,000 on Folia. Every one of those losses was
inside the recorded gap. The Folia iteration that lost nothing is the inconclusive arm doing its
job: its kill landed between ticks, the coverage property was never exercised, and the run passed
only because the other two iterations did lose something.

*Not established, and each of these is a real hole rather than a formality:*

* There is no asserted bound on *how much* a crash may lose. The gate checks coverage, not size.
* The gate exercises one loss mode: records staged in memory when the kill lands. Two others now
  produce a gap and neither is tested — a journal or store write that fails, and records capture had
  to drop because a ring was full or the clock could not order them. Both were silent until
  2026-09-20; the first even logged that the records were safe in the ring when they were already
  gone.
* Beyond 32 concurrent capture threads, records can be overwritten with no drop counted and so no
  gap at all. That is a known defect with no fix yet, recorded in
  [ADR-0012](docs/decisions/0012-capture-transport.md).

**An interrupted rollback resumes.**
A rollback is now a durable operation: recorded before the first block is touched, checkpointed at
each chunk boundary, marked finished only when it is, and reported at startup and by `/trace status`
if it was not. `rollback-resume` cancels a running rollback, which stops it at a chunk boundary with
work left, and then continues it. The scenario asserts what is deterministic: the first run restored
some but not all of the 240 positions, the resumed run restored exactly the rest and ended `DONE`,
and every position is `STONE` again when the world is read back block by block. The split between
the two runs depends on when the cancellation lands and is not asserted or published. See
[ADR-0015](docs/decisions/0015-rollback-operations-and-resume.md).

*Not established:* resuming after a real crash, as opposed to a cancellation, is not covered by a
test. Nor is the cost of checkpointing, which is measured nowhere and claimed nowhere. A chunk whose
region is too busy to accept the work is counted and left for a resume, and that path has no test
either: it was also where a review found a rollback writing blocks the history never named, because
a task the wait had given up on was still holding the fold's arrays.

### Defects this milestone found in its own earlier work

Recorded because they are the argument for the harness existing, and because two of them were
silent:

* the journal's first frame was discarded on every run, because the store's "already applied"
  watermark started at zero and the first frame lives at position zero;
* a rollback could write blocks no recorded change ever named: when a region did not accept a chunk
  in time, the abandoned task kept a reference to the fold's arrays, which the next chunk then
  overwrote;
* a failed journal write lost its records and logged that they were safe, and dropped records never
  became a gap at all, although the whole reason dropping is allowed is that it does;
* reopening the journal truncated every frame written by previous runs, because a per-run salt was
  being treated as end-of-log, and a unit test asserted that loss as intended behaviour;
* a crash gap could start after an event it had to cover, because its lower bound was the newest
  journalled capture rather than the low-water mark the frame header already carried;
* the store served the consumer thread, a rollback and a command from one JDBC connection with no
  lock;
* and the gate said "zero allocation" about a hand-written stand-in for the encoder.

## Open questions carried forward

Tracked in the ADRs rather than here, but the ones that shape upcoming work:

* **M1** — whether JMH's `gc.alloc.rate.norm` can report exactly `0.0` for a provably empty
  benchmark. If it cannot, the allocation gate's definition is a decision for the owner, not a
  quiet loosening.
* **M1** — how CI breaks a block with no player connected: an internal fake player, a protocol bot,
  or a synthetic event. Whichever wins, the limitation is documented with the result.
* **M2** (settled) — of the five critical design defects found by adversarial review of the
  walking-skeleton design, four are fixed and tested: gap coverage after `kill -9`, the crash-window
  lower bound, journal frame integrity, and the fence that drains and journals before a rollback
  plans. The fifth, block-entity capture on region threads, is M4 work and until then a rollback
  says plainly that container contents were not captured in this build.
* **M2** (open) — a rollback interrupted by a real crash, rather than by a cancellation, is not
  covered by a test, and neither is the cost of checkpointing. Both are named in
  [ADR-0015](docs/decisions/0015-rollback-operations-and-resume.md) rather than left to be
  discovered.
* **M3** (unblocked) — SPIKE-2 has its CoreProtect database: the owner supplied a real 12.35 GB one
  on 2026-09-21, and a first observation of its density, the method, and the privacy and clean-room
  reasoning are in [provenance.md](docs/decisions/provenance.md). What is still missing is the part
  that matters: a committed, repeatable measurement task. Until that exists and its output is under
  `benchmarks/results/`, no storage-density figure may be published, including the one already
  observed.
* **M9** — the CoreProtect compatibility bridge needs classes in the `net.coreprotect` package, and
  parts of that API's wire format are undocumented. Scope and legal review pending.
* **Positioning** — the owner has identified Oasis and supplied an analysis
  ([`docs/competitive/oasis.md`](docs/competitive/oasis.md)). Its performance figures are the
  competitor's own unverifiable claims and stay out of every public surface. Two owner decisions
  come with it: the README's URL, and whether the AGPL question it raises reopens ADR-0006 (default:
  no).
