# Roadmap

Milestones run in order. Each one has a definition of done that has to pass before the next starts,
and an unmet item is reported as unmet rather than carried quietly.

| # | Milestone | State |
|---|---|---|
| M0 | Skeleton: modules, build, licences, a plugin that loads on Paper and Folia | **done** |
| M1 | Benchmark and crash harness — built *before* any feature | **done** |
| M2 | Walking skeleton: one event type captured with zero allocation, journalled, sealed into a shard, queried back, rolled back | **done** |
| M3 | Storage engine: sharding, sealing, compaction, manifest, dictionaries, blobs, retention, purge, verify, quarantine | **done** |
| M4 | Full capture: every kind and cause, block entities, entities, containers, sessions | in progress |
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
* At M2, excess capture threads could overwrite a shared slot without a gap. M3 contains this
  with counted rejection and persistent conservative loss bounds; see
  [ADR-0019](docs/decisions/0019-producer-exhaustion-containment.md). Reclamation/spill and an
  actual killed-process exhaustion test remain unestablished.

**An interrupted rollback resumes.**
A rollback is now a durable operation: recorded before the first block is touched, checkpointed at
each chunk boundary, marked finished only when it is, and reported at startup and by `/trace status`
if it was not. `rollback-resume` cancels a running rollback, which stops it at a chunk boundary with
work left, and then continues it. The scenario asserts what is deterministic: the first run restored
some but not all of the 240 positions, the resumed run restored exactly the rest and ended `DONE`,
and every position is `STONE` again when the world is read back block by block. The split between
the two runs depends on when the cancellation lands and is not asserted or published. See
[ADR-0015](docs/decisions/0015-rollback-operations-and-resume.md).

*M3 update:* a real killed-process resume now passes on both pinned platforms, including compaction
before resume and a block-by-block world check; see [ADR-0020](docs/decisions/0020-crash-resume-world-persistence.md).
The cost of checkpointing is **not measured**. A chunk whose
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

## M3 implementation and proof history

**Final status:** complete for the scope and gates in the
[final audit](docs/design/m3-final-audit.md). The dated implementation history below
is preserved; the final close-out explicitly resolves its implementation blockers.

The file-level plan and pre-implementation self-attack are in
[m3-execution-plan.md](docs/design/m3-execution-plan.md). ADR-0016 through ADR-0020
record implemented decisions and deviations from that plan.

* **Storage publication:** format 3 moves hot rows into manifest.db, removing the false assumption
  that WAL commits across attached databases are atomic together. Legacy hot rows migrate once;
  unknown future formats are rejected before schema mutation. Compaction retains absolute event
  identities, pins reader snapshots, and retires old files after readers release them.
* **Retention/purge:** explicit cutoff and actor/time APIs rewrite sealed and hot history, remove
  blob references, publish refusal gaps and audit metadata, and persist replay exclusions.
  These are history removal, not identity erasure or secure deletion.
* **Verification/quarantine:** re-read seal digests, structural checks and content checks detect
  damaged history; quarantine and its refusal gap commit together. Legacy shards lacking trusted
  digests remain unverified. Blob corruption is checked separately.
* **Opaque blobs:** versioned content identities, durable payload/attachment operations, stable
  lookup across compaction, and explicit unreferenced collection. Capture-side atomic event/blob
  journalling and Minecraft payload interpretation remain M4 work.
* **Proof:** storage tests compare complete records and scan suffixes, inject valid-SQL corruption,
  fail quarantine publication, kill child JVMs around rewrite publication and blob attachment,
  and reopen stores. Serial Paper/Folia tests cover integration, cancellation resume, real-crash
  resume, journal loss coverage, and purge/quarantine refusal without world mutation.
* **SPIKE-2:** committed aggregate-only dbstat tooling and reports are under
  [benchmarks/results/density](benchmarks/results/density). The same reader counts both sides;
  [STORAGE-DENSITY.md](benchmarks/STORAGE-DENSITY.md) states denominators and exclusions. Different
  data and semantics do not establish production savings, a migration ratio, or logger superiority.

**Initial M3 proof gaps (historical; follow-up below):** automatic maintenance scheduling and configuration, bounded
maintenance budgets/cancellation, migration interrupted mid-transaction, large-data maintenance
memory/latency gates, and power-loss durability. The plan's additive multiset accumulator and shared
storage-contract expansion are not implemented; SQLite-specific complete-field tests are the current
oracle. Maintenance holds the writer monitor; its effect on capture lag is **not measured**.
StoreStats is not total physical disk accounting; use the density task's explicit categories.
Purge leaves dictionaries, journals, legacy hot.db, quarantined files, backups and filesystem free
space outside its erasure scope. Blob storage does not prove container rollback fidelity.

M3 absorbs the real-crash resume debt because compaction must preserve resumability, and contains
producer exhaustion because silent overwrite would invalidate storage preservation claims. Full
transport reclamation/spill remains open. The contended-chunk apply path remains explicitly untested;
M3 does not change its scheduling protocol. M4 has not started, and M3 is not declared done.

### 2026-09-21 follow-up: five-step execution and audit

The [completion plan](docs/design/m3-completion-plan.md) and
[completion audit](docs/design/m3-completion-audit.md) distinguish resolved gaps
from remaining requirements. Migration and producer exhaustion now have asserted
process-kill/reopen branches. Bounded/cancellable rewrites, typed template-backed
configuration, consumer scheduling and explicit retention opt-in are implemented;
scheduled passes are asserted on both pinned platforms. Shared EventStoreContract
now covers complete-field compaction/suffix preservation, purge replay exclusion,
retention boundaries and durable blob references. Cancellation/orphan cleanup and
bounded sweep continuation have regression tests that failed before their fixes.

Maintenance costs are now measured for the committed synthetic capture/consumer
workload under [results/maintenance](benchmarks/results/maintenance); the tool and
scope are in [MAINTENANCE.md](benchmarks/MAINTENANCE.md). Completion-budget cases
merged but dropped captured records when the ring filled. Short-budget cases
deferred and recorded no drops in that fixture. This does not establish eventual
progress or harmless maintenance under production load.

**Still incomplete:** resumable progress beyond admission/deadline limits, bounded
scheduled verify, scheduled WAL checkpoint lifecycle, the additive multiset
accumulator, and shared quarantine fault-injection proof. Seal/verify cost, native
memory and real-server maintenance tick impact are **not measured**. Power-loss
and arbitrary-instruction crash guarantees remain unproven. Earlier M2 capture and
rollback limitations still apply except where a named follow-up gate resolves them.
M3 remains **in progress**; M4 has not started.

### 2026-09-21 final close-out

The [final plan and self-review](docs/design/m3-final-plan.md) led to retained
incremental compaction, seal and retention progress; scheduled content verification
and checkpoint retry; the additive multiset accumulator; and shared quarantine
fault-injection proof. Gates assert progress, cancellation, publication, corrupt
versus unverified content, held-reader checkpoint deferral, successful truncation,
and actual scheduler effects. Process kills cover incremental copy and both sides
of publication. The complete-removal audit regression failed before its fix.

The [final audit](docs/design/m3-final-audit.md) links the committed full serial
regression and the separately scoped post-audit-fix retest. Both pinned platforms
pass their exercised scenarios. Committed maintenance costs now include seal,
content verify and checkpoint work; scheduled scenarios record tick observations.
SPIKE-2 separately measures explicit and incremental sealing through the same
aggregate reader, without reopening the private database.

Cooperative budgets are not hard deadlines. No progress bound under overload or
long-held readers, power-loss guarantee, production latency claim, native-memory
bound or density savings ratio follows. Full structural/blob verification remains
explicit; unfinished maintenance restarts safely after process death. Earlier
*Not established* notes retain their original scope except where a named gate in
the final audit resolves them. M3 is **done**; M4 has not started.

## Open questions carried forward

M4 implementation begins with the [real-client gate](docs/design/m4-client-gate.md),
following the [file-level plan and self-review](docs/design/m4-capture-plan.md).
Historical M2 notes above retain their original scope. The committed
[client evidence](benchmarks/results/client-capture/2026-09-21-094916-3dc19b5f3401/complete.json)
passes actual creative-mode client break/place, cancellation, unchanged attempts
and same-tick reversion on both pinned platforms. Each action asserts its capture
branch, post-tick world state and exact exposed row content; Folia asserts separate
region ownership. The client shares server packet codecs, so independent wire
compatibility is not established. Full capture remains incomplete.

The [bounded payload queue](docs/design/m4-bounded-payload-queue.md) now keeps event
words and payload bytes in one fixed-capacity entry until acknowledged. Its named
branch, corruption-refusal, concurrency and process-kill tests have
[committed evidence](benchmarks/results/payload-queue/2026-09-21-100331-232b3a53d0c4/complete.json).
This is a transport prerequisite, not live payload capture: atomic journalling and
recovery that converts rejection bounds into GapRecords remain unimplemented.
Queue allocation and performance are **not measured**; power loss and crashes
partway through an offer/rejection remain unproven.

**2026-09-22 payload integration follow-up:** [ADR-0023](docs/decisions/0023-bounded-payload-handoff.md)
connects the bounded queues to the live consumer and startup recovery. Event,
payload, reference and applied watermark publish atomically; acknowledgement follows
journal force and store commit. Failed handoffs prevent later ordinary frames from
advancing the watermark, and successful flush requires persisted rejection gaps.
Rollback preflight refuses opaque payloads and non-block history before applying
chunks. Format 4 intentionally prevents the preceding format-3 runtime from opening
the upgraded store.

The [committed-source evidence](benchmarks/results/payload-integration/2026-09-22-095619-cba62e8af72b/complete.json)
contains named failure/contract/process-kill tests and serial Paper/Folia payload,
real-client, block rollback and cancellation/resume regressions. Payload server
records are synthetic and use already-persisted dictionary ids. Ordered dictionary
publication, full state/NBT fidelity, natural payload listeners and restoration
remain incomplete. Power loss and arbitrary-instruction crashes remain unproven;
payload allocation and performance are **not measured**. This follow-up resolves
the transport integration prerequisite above, not M4's full capture gate.

**2026-09-22 confirmation foundation:** the
[completion plan](docs/design/m4-completion-plan.md) and
[ADR-0024](docs/decisions/0024-confirmation-loss-and-net-observations.md) replace
unconfirmed before=after rows with counted, persistent loss. Repeated positions
within a producer tick keep the first before-state and final observation; mixed
attribution on a changed position is gapped. Packed field overflow is rejected.
Flush now refuses if capture-loss gaps cannot be persisted; its injected-failure
regression failed before the fix. Runtime confirmation checks ownership and chunk
availability before reading. The `confirmationEvidence` task records the scoped
branch/allocation and existing serial server regressions, with
[committed-source results](benchmarks/results/confirmation/2026-09-22-142522-a49e3ad075b0/complete.json).
Both pinned platforms pass the existing client, rollback and resume scenarios.
The new edge cases are unit/consumer gates, not new natural event coverage.
Full-state capture, dictionary publication and the remaining M4 families
are still incomplete. Earlier evidence retains its original scope.

**2026-09-22 dictionary publication follow-up:** the
[plan/self-review](docs/design/m4-dictionary-publication.md) and
[ADR-0025](docs/decisions/0025-durable-dictionary-publication.md) move live actor/world
registration into a bounded worker queue. IDs publish after forced atomic file
replacement; missing dependencies become counted capture loss. Startup obtains
the writer lock before dictionary writes. Strict parsing rejects conflicting or
out-of-range ids. The material dictionary keeps its previous fidelity.
Unit, failure and announced process-kill gates are implemented; the
`dictionaryEvidence` task collects fresh allocation and serial pinned runtime
gates. Committed-source results are pending. Dynamic world-load events and a real
player acting during failed registration are not yet exercised. Full block states,
additional causes and natural payload families remain incomplete; dictionary/queue
costs and listener allocation are **not measured**.

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
* **M2** (partly resolved in M3) — real-crash resume now has a passing gate; checkpoint cost remains
  **not measured**, and contended-chunk apply remains untested. See ADR-0015 and ADR-0020.
* **M3** — SPIKE-2 now has a committed repeatable measurement task and aggregate baseline report.
  The original hand observation is not independent evidence. Private input is rerunnable by its
  holder, not reproducible by someone who lacks it; no source rows or database files are committed.
* **M9** — the CoreProtect compatibility bridge needs classes in the `net.coreprotect` package, and
  parts of that API's wire format are undocumented. Scope and legal review pending.
* **Positioning** — the owner has identified Oasis and supplied an analysis
  ([`docs/competitive/oasis.md`](docs/competitive/oasis.md)). Its performance figures are the
  competitor's own unverifiable claims and stay out of every public surface. Two owner decisions
  come with it: the README's URL, and whether the AGPL question it raises reopens ADR-0006 (default:
  no).
