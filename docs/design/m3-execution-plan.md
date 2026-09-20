# M3 execution plan and adversarial review

Date: 2026-09-21. Status: implementation plan, not a completion claim.

The existing `m3-storage-engine.md` is a proposal, not authority to defer the
owner's requested blob subsystem. This plan supersedes its scope and debt choices.
Existing identifiers below were read in the working tree before this file was
written. Names explicitly labelled **new** are proposed, not claims of existing APIs.
No forbidden project source was consulted. No source database event row was read.

## Storage foundation and migration

Files: `trace-storage-sqlite/.../SqliteSchema.java` (`FORMAT_VERSION`,
`createManifest`, `createHot`, `createShard`); `SqliteEventStore.java` (`open`,
`initialiseMeta`, `append`, `seal`, `writeShard`, `publishShard`, `scan`,
`deleteOrphanShards`, `nextShardId`, `ShardRef`); `ShardKeys.java` (`key`,
`timestampOf`, `lowKey`); `KeysetRowSource.java` (constructor, `pageSql`).
Package prefixes here and below are `src/main/java/in/gravaxis/trace/` under the
named module; tests mirror them under `src/test/java`.

Move the hot table into manifest.db with an explicit format migration. Copy the
legacy hot table while the data-directory lock is held, in the transaction that
advances the format; leave the legacy file untouched until that commit succeeds.
Reject future formats before mutating schema. A restart after commit must not copy
the old rows again. Fix connection cleanup on failed open.

Why: attached databases in WAL mode are not atomic as a set. Existing comments in
`createHot`, `append` and `publishShard` assume otherwise. Source:
https://www.sqlite.org/wal.html and https://www.sqlite.org/lang_attach.html,
read 2026-09-21. Combining hot rows, watermark, shard publication and deletion in
one SQLite transaction removes that particular split-commit failure.

Proof: test-first migration fixtures for format 2 with hot and sealed rows,
watermark and unfinished operation; repeated reopen; refusal of a newer format;
failure injection before/after publication. Assert both the selected recovery
branch and exact row identities. Does NOT prove physical power-loss behavior,
filesystem directory durability, or repair a legacy store already inconsistent.

## Compaction

Existing: `SqliteEventStore.seal/writeShard/publishShard/liveShards`,
`MergingCursor.close`, `CursorPosition`, `ScanPlan.resumeAfter`,
`EventStoreContract.resumingReturnsExactlyTheRemainder`.
New: a maintenance API/result in trace-storage-api, a shard lifecycle helper and
compaction tests in trace-storage-sqlite.

Reserve a unique output identity before writing. Stream sorted rows, decode to
absolute timestamps then rebase using the minimum actual timestamp. Plain INSERT
must reject duplicate identities; never deduplicate by ignoring conflicts.
Re-read output and compare exact row count and canonical digests before one
manifest transaction publishes output and retires inputs. Keep retired files
until all scans holding them close; startup sweeps only known retired/orphan files.
Scan snapshots must include the hot table and shard selection consistently: paging
the hot table through autocommit while sealing can otherwise omit or double rows.

Proof: oracle compares every field and multiplicity, both scan directions,
different bases, identical keys in separate shards, a cursor open during merge,
and a stored resume cursor before merge versus its exact suffix afterwards.
Inject failures around output force and manifest commit; reopen twice.
Does NOT prove a performance improvement, optimal window size, or bounded heap
independent of the number of simultaneously opened shards. All costs: not measured.

## Retention and purge

Existing: `EventStore.gapsBetween`, `SqliteEventStore.recordGap`, `rollback_op`,
`RollbackService.refusalForGaps/resume`, `ActorDictionary.load/register/save`.
New: explicit maintenance requests/results, durable expiration/purge records,
and tests alongside EventStoreContract and SQLite lifecycle tests.

Retention is disabled by default; explicit cutoff only until configuration and
scheduling are proven. Whole expired shards can retire; a cutoff through a shard
requires filtered rewrite. Purge selects actor and time window and includes hot
rows, sealed rows, and attached blobs. Publish replacements, loss/refusal metadata
and audit together. Keep a replay filter/tombstone so retained journals cannot
resurrect purged history. Do not erase actor dictionary identities as a side effect.
Prevent destructive maintenance racing active rollback scans; paused operations
must refuse across newly removed history rather than silently finish a suffix.

Proof: cutoff boundaries, mixed actors, empty selection, old shards without actor
summaries, hot rows, repeated execution, replay after purge, failed publication,
paused resume refusal and held-reader behavior. Every test asserts whether rows
were removed, retained, deferred or refused. Does NOT prove secure erasure from
journals, backups, filesystem free space, or privacy-law compliance. This is
history purge, not identity erasure. No irreversible automatic default.

## Verify and quarantine

Existing: `GapRecord.Reason.QUARANTINE`, `SqliteEventStore.scan/recordGap`,
`StoreException.Reason.CORRUPT`, `StoreConsumer.flushAndSeal`,
`SqliteSchema.createShard`. New: canonical row digest, verification result,
transactional quarantine, shared contract tests and SQLite corruption tests.

Write expected content digests while sealing, then re-read the closed output.
Verify both SQLite structure and semantic content, counts and bounds. Old shards
without a trusted digest are explicitly baseline-unverified; reading them now
cannot prove historical integrity. Quarantine changes read membership and writes
its gap/audit atomically, retaining the physical file for investigation. A failed
new seal retains hot rows and does not create a false loss gap. Missing live files
must never be recreated as empty SQLite files. Preserve CORRUPT through flush.

Proof: healthy control, deliberately modified valid SQL payload (integrity_check
still succeeds), malformed file, missing file, repeated quarantine, atomic failure
at gap insertion, scan/rollback refusal. Does NOT prove authenticity against an
attacker who changes both metadata and payload, nor every hardware failure mode.

## Blob storage

Existing: `EventRecords` packed sidecar field, `JournalFrames.TYPE_EVENTS`,
`JournalWriter.appendEvents/writeFrame`, `JournalReader.replay`,
`TraceRuntime.recover`, `MutationBatch.add`, `KeysetRowSource.readRow`,
`MergingCursor.emit`; no working blob read path exists.
New: immutable blob identifier/value API, blob persistence and attachment API,
blob reference in scans, blob tests and journal round-trip fixtures.

Storage owns identity independently of producer slots. Store opaque payload bytes
with version, length and checksum; return stable references through scans so moving
rows between shards never changes blob identity. Make payload durable before a
referencing event is accepted, and replay attachment idempotently. Enforce input
size limits, reject missing/corrupt references, and collect only unreachable blobs
after publication and reader release. Migration treats old rows as having no blob.
This is M3 opaque storage; NBT capture, DataFixer and region-thread extraction are M4.

Proof: exact binary round trip including empty payload, dedup/collision validation,
restart, orphan payload, missing payload refusal, compaction/reference preservation,
purge of one of several references and then the final reference. Crash tests must
exercise payload-before-reference and published-reference branches separately.
Does NOT prove Minecraft NBT fidelity, zero-allocation blob capture, container
rollback correctness or compression benefit. Those remain not measured/unproven.

## SPIKE-2 repeatable density task

Existing: `benchmarks/build.gradle.kts`, `BenchmarkReportTask.run/write/renderTable`,
`SqliteEventStore.open/append/seal`, `RecordBatch`, pinned `libs.sqlite.jdbc`.
New: `StorageDensity` and `DensityReader` in benchmarks main sources; synthetic
fixture and privacy tests; `storageDensity` Gradle JavaExec task. No new driver.

Build and commit the tool before running the real input. Accept the source path as
an explicit local property; never copy it or make it a Gradle cache input. Open
read-only with query_only. Enumerate only schema object names/types/ownership
(never sqlite_schema.sql), count rows per table, and aggregate dbstat pages/bytes
by object. Resolve identifiers from metadata, quote them correctly, expose no raw
SQL option and print no exception containing source values. No rows, names, UUIDs,
IPs, coordinates, or value distributions. Canary tests must check every output.

Measure synthetic Trace stores through the same DensityReader using the real
append/seal path. Record seed, event mix, count, timestamp span and seal interval;
include more than one span because packed key width changes density. Do not model
the private database by sampling its rows. Count table and index pages separately,
freelist, unassigned pages, main-file length and WAL/SHM separately. For Trace also
state whether manifest/hot/shards, dictionaries, journal/rings and blobs are present
or excluded. Assert page accounting reconciles; do not equate pgsize to payload.

Commit only aggregate reports and reproducible code, never database files or
player data. Report environment and code commit. Avoid automatically replacing
the existing server benchmark table with a density-only run. Failed/skipped runs
must not reuse old outputs. Run the expensive private page walk only after fixture
tests pass. The supplied first observation is not a publishable measurement.

Proof: planted private strings never emitted, read-only write attempts fail,
table/index attribution matches known fixture, free pages accounted, deterministic
Trace event count, exact accounting reconciliation. Does NOT prove equivalent
semantics/workloads, production Trace density, a migration ratio, throughput or
which logger is better. No headline ratio. A private input is rerunnable by its
holder, not independently reproducible by someone who lacks that input.

## Debt choices and serial validation

Absorb real-crash resume testing: maintenance changes the history under a durable
cursor. Extend `CrashInjectionTask`, `TracePluginBridge` and the existing
`RollbackResumeScenario` pattern with a real killed-process scenario. Test both a
safe stored window and explicit refusal when recovery/maintenance adds a gap.
Do not weaken gap refusal just to obtain a green resume test.

Absorb containment of producer exhaustion, not a full transport redesign:
`CaptureService.newProducer` must never open a second writer on the final SPSC
ring. Excess producers must take an explicit counted rejection path covered by a
durable conservative gap. Test simultaneous overflow producers and restart; rerun
both allocation instruments if the capture path changes. Full reclamation/spill
remains separate debt. A duplicate-key counter alone cannot detect overwritten rows.

Leave the contended-chunk apply debt explicitly open unless M3 changes that path;
storage exclusion/leases must not silently alter its scheduling semantics.

`TestServerService` already exists. Verify all server tasks use the shared service;
run only one Gradle invocation booting servers at a time. Unit/property/SQL tests
first, serial integration/crash runs last. No server benchmark concurrent with the
private density walk. Performance and checkpoint/maintenance costs: not measured
until committed reports exist.

## Self-attack before implementation

1. Attached WAL atomicity invalidates the old foundation: migrate hot rows into
   manifest before maintenance; do not patch around it with optimistic comments.
2. Digest computed from insert inputs can agree with itself while disk is wrong:
   independently re-read output. Hashes are detection, not proof against an attacker.
3. Checking gaps before opening a scan races purge/quarantine: put exclusion and
   scan acquisition under the same store synchronization/lease protocol.
4. Holding files alone is insufficient: paged hot reads need a stable snapshot.
5. A format bump without ALTER/copy is no migration; unknown future format must be
   rejected before CREATE/PRAGMA changes. Old checksum baselines cannot be invented.
6. Deletion can be undone by journal replay: persist selection tombstones, not just
   remove rows. Physical erasure remains out of scope and must be named honestly.
7. A digest using XOR hides paired duplicates; require counts, plain INSERT, and
   field-by-field oracle tests. A timestamp rebased below zero must not wrap.
8. Blob identities based on producer sidecars inherit the known slot collision;
   use storage identities and expose references to readers explicitly.
9. Private identifiers can contain quotes/control characters; validate metadata
   membership, quote SQL identifiers, and suppress raw names in report serialization
   unless they match the fixed safe schema-name policy. Never select source DDL.
10. Density-only output can shadow the last server benchmark report; use a separate
    report namespace and explicit not-measured fields, not stale copied results.
11. A crash test that kills before rollback starts proves nothing; require a
    durable started/checkpoint marker and independently inspect the world afterward.
12. Scheduling a maintenance task is not proof it runs. If automatic maintenance
    lands, the integration gate must await and assert a real completed pass.

Each subsystem remains incomplete until its stated tests pass. This document does
not assert implementation, measurements, or independent reviewer participation.
