# M3 final audit — 2026-09-21

M3 is complete for the storage-engine scope below. M4 has not started. This closes
the implementation blockers in the earlier completion audit; it does not turn its
historical measurements or unproven guarantees into passes.

## Acceptance ledger

| Requirement | Implemented behavior and exercised gate |
|---|---|
| Publication and migration | Format-3 single-manifest transactions; StorageMigrationTest and actual process kills before/after migration commit in StorageCrashTest |
| Compaction and stable cursors | Canonical ordered digest plus additive multiset/count; independently re-read output; inherited EventStoreContract checks complete fields, both directions and stored suffixes for explicit and incremental compaction |
| Progress beyond one step | ShardWork retains keysets and digests across bounded row steps; IncrementalMaintenanceTest uses inputs larger than its budget, asserts PROGRESSED and COMPLETED, and appends between steps |
| Seal and cancellation | Bounded hot prefix, independently verified unpublished output; tests interleaved append, cancellation at forced-output boundary, stale-work invalidation and pinned-reader cleanup |
| Retention and purge | Explicit atomic actor/time purge and retention; scheduled incremental retention with opt-in configuration, refusal gaps, replay exclusions and audit. Contract and incremental tests assert cutoff, reader deferral, no resurrection and preserved shared blobs |
| Verify and quarantine | Incremental scheduled content/count/bounds checks with distinct healthy, unverified and quarantined outcomes; explicit full structural/blob verification remains available. Shared quarantine fault injection asserts rollback, retry and reopen |
| WAL lifecycle | Nonwaiting checkpoint attempt inspects SQLite's busy result and retries. Both direct and scheduler tests hold a real snapshot, assert deferral, then assert truncation after release |
| Actual scheduling | Unit gate observes scheduled quarantine, checkpoint retry and sealing. Paper/Folia scenarios observe consumer-driven compaction and require tick samples; no direct compact call substitutes for the scheduled pass |
| Blob storage | Versioned payload identity, stable event references and explicit collection; shared contract and crash tests cover persistence and attachment. Scheduled expiration hides references in its publication transaction and defers physical reference reclamation to collection |
| Crash and capture debts | Producer-exhaustion process-kill proof; incremental copy/forced-output/commit kills; real-crash rollback resume on both pinned platforms. Contended-chunk apply remains outside this storage change |
| SPIKE-2 | Committed aggregate-only dbstat reader, existing private baseline and refreshed synthetic Trace fixtures for both sealing modes; no source database or player rows copied |

The final review also caught a complete-removal audit defect. Its regression failed
before the fix: an absent output was given a future shard id, and scheduled
retention counted kept rows as affected. Removal now records the removed count and
the existing no-shard sentinel when no replacement exists.

## Evidence and revision boundaries

* [Full regression at a7bbf2b](../../benchmarks/results/m3-validation/2026-09-21-075956-a7bbf2bb8bd6/complete.json):
  unit gates, serial integration, cancellation resume, journal crash, real-crash
  resume, purge, quarantine and scheduled maintenance. No-loss crash iterations
  remain inconclusive; the journal sets passed because loss was actually exercised.
* [Post-audit-fix evidence at 85dd59f](../../benchmarks/results/m3-validation/2026-09-21-080832-85dd59fc962f/complete.json):
  unit gates including the new audit regression and serial purge, quarantine and
  scheduling on both platforms. This scope is explicit in the generated report;
  older integration/crash reports were not copied under this revision. The scoped
  correction changed audit values, not publication or recovery behavior.
* [Maintenance costs at 383b47e](../../benchmarks/results/maintenance/2026-09-21-073655-383b47e79ee6/measurements.json):
  both budget profiles completed a pairwise merge. Short-budget fixtures retained
  progress across calls and observed no drops; some long-budget fixtures dropped
  capture. Separate fixtures measure seal, content verify and checkpoint calls.
  Individual native/force operations can exceed the cooperative budget. The later
  audit correction does not justify relabelling these timings as a new measurement.
* [Synthetic density at fcc56df](../../benchmarks/results/density/2026-09-21-080557-fcc56dff4830/density.json):
  explicit-window and incremental-prefix sealing are separate measurements through
  the same reader. Private input was not supplied. STORAGE-DENSITY.md distinguishes
  the preceding reader output whose Gradle invocation failed configuration-cache
  serialization from this successful invocation.

Generated environments identify the code, command, JVM and machine. The evidence
collector JVM is not the server JVM. Server-window tick observations are in each
scheduled scenario report; they are not a causal latency comparison or a bound.
Build, module graph, formatting and README-table consistency checks pass separately.

## Limits that completion does not remove

* Budgets yield at row-page boundaries. Disk I/O, force, commit and checkpoint are
  not hard-deadline operations. Overloaded capture or long-held readers can defer
  work; no finite WAL-size or completion-time guarantee follows. Configuration
  defaults are safety choices, not measured optimums.
* Live-process maintenance progress is retained. Process death discards unfinished
  output safely and restarts from live inputs; persistent maintenance-job resume
  is not claimed. Explicit all-selection rewrite APIs can return CONTENDED when
  their admission/deadline limits are exceeded.
* Scheduled verification checks sealed content incrementally. Full SQLite
  integrity_check and blob audits are explicit operations; neither is claimed to
  be incremental. Legacy content without a trusted digest has no historical
  integrity proof. Digests do not authenticate malicious manifest replacement.
* Tick observations cover a small synthetic capture/seal/wait window with no
  control arm. Causal production tick overhead, native-memory bounds and memory
  independent of simultaneously opened shards are **not measured**. Sampled Java
  heap maxima are not true peaks. Older overload/deferred results remain valid for
  their own code and workloads.
* Power loss, failing disks and arbitrary instruction interruption are unproven.
  Purge is history removal, not erasure of dictionaries, journals, legacy files,
  quarantine files, backups or free space. Scheduled retention may first refuse a
  conservative expired window while later passes reclaim its remaining files.
* Blobs are opaque storage, not atomic captured event/blob journalling, NBT fidelity
  or container rollback. The density datasets and denominators differ; there is no
  production savings ratio. Earlier capture/listener and rollback limitations,
  including contended chunks and overlapping rollbacks, remain as recorded.

No M3 implementation blocker listed in the earlier audit is being carried into M4.
The limits above describe the scope of the passing gates, not additional passes.
