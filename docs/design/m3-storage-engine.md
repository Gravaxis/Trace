# M3 storage engine: reviewed design proposal

**Execution update:** see `m3-execution-plan.md` and ADR-0016 through ADR-0020.
The current task includes opaque blob storage and producer-exhaustion containment;
the deferrals below are historical proposal choices, not the implemented scope.
Single-database WAL publication also replaces this proposal's inherited attached
database assumption. Validation limits remain explicit in the execution ledger.

* **Status:** proposal, reviewed, **not a decision**. No ADR has been written from it and no code
  exists. Each subsystem gets its ADR (0016 onwards) as it lands, not before.
* **Date:** 2026-09-21
* **Milestone:** M3

Four subsystems were designed against the code as it stands, each was then attacked by a reviewer
whose job was to break it, and the survivors were reconciled. The critiques found **13 fatal and 27
major defects**, and one subsystem came back `broken`. That is the useful part of this document: the
designs are a starting point, the defects are the traps, and the order exists because three plans
collided on the same file.

Everything below was checked against the tree. Where a plan's premise disagreed with the code, the
code won.

---

## The order, and why

Steps 1 to 9 boot no server. Step 10 is the single serialised server window, because only one test
server runs at a time on the development machine.

1. **Format-3 migration and the shard lifecycle.** Everything else writes to this schema.
2. **`publishShard` signature split, `ShardKeys` precondition, `ShardRef.id`, missing-file-is-CORRUPT,
   the `scan()` un-register path.** Small, load-bearing, three subsystems depend on them.
3. **The row encoding and both digests**, written inside `writeShard`'s existing insert loop.
4. **`quarantineShard`**, the gap it writes, and the reason-specific refusal message. Contract tests
   in `EventStoreContract` so every future backend inherits them.
5. **Verify**, including the seal-time re-read check and the bit-flip test with its control arm.
   Cheapest of the three, and it is what makes the other two's failures loud.
6. **Benchmark reporting plumbing.** Must precede any committed measurement.
7. **SPIKE-2 density.** Off-server, independent of 1 to 5, closes the oldest open item in ROADMAP.
8. **Compaction.** Needs 1, 2, 3 and 4 to be right first.
9. **The configuration system, then retention and purge.** See the note below: ADR-0005 claims
   configuration was implemented in M2 and it was not.
10. **One serialised server booking:** crash-during-merge, crash-during-sweep, a maintenance pass on
    Folia, resume-after-a-real-crash, purge-then-rollback, and a rollback refused across a
    quarantined shard.

The shape: everything that changes what is on disk comes before everything that reads it, everything
that can fail comes before the thing that reports the failure, and the resource that serialises all
work is spent once, at the end, when there is something worth shooting at.

---

## Six things that must be built once, not three times

**One format-3 migration.** All three storage plans independently added columns to the `shard` table
and independently bumped `FORMAT_VERSION` to 3. All three are no-ops on an existing data directory,
because the table is created with `CREATE TABLE IF NOT EXISTS` and keeps its nine columns forever.
Two reviewers found this separately. One explicit `ALTER TABLE` sequence lands first, guarded by the
stored version, with a test that opens a format-2 directory written by the current build.

**One maintenance service.** A single async fixed-rate task running verify, then compaction, then
retention, under one budget and one cancel flag, with a bounded join in `TraceRuntime.close()` before
the store releases its lock, a heartbeat, an unconditional `/trace status` line, and one
`PRAGMA wal_checkpoint(TRUNCATE)` at the end of each pass. That last one matters: `wal_autocheckpoint`
is disabled and the only checkpoint in the tree today is in `close()`, so sustained maintenance
writes would grow the WAL without bound. The Bukkit-free budget interface lives in storage; the
tick-p99 and back-pressure implementation stays in `trace-paper`.

**One path from "this shard is unreadable" to a gap.** `EventStore.quarantineShard(...)`, writing the
state change, the `QUARANTINE` gap and the audit row in one commit, exposed on the interface so the
contract suite covers every backend. Composing it from `recordGap()` plus a separate state update is
forbidden: the half-committed state is exactly the failure this prevents.

**One audit table.** Retention's `audit` and verify's `shard_quarantine` are the same table.
Append-only, never itself subject to retention.

**One row encoding, two accumulators.** Compaction needs an order-independent multiset digest;
verify needs a per-shard content digest. One canonical fixed-width big-endian encoding feeds both: a
streaming SHA-256 finalised with the row count, and an **additive** mod-2¹²⁸ accumulator. XOR is
inadmissible — it cancels even-count duplication — and the negative test that duplicates one row
exactly twice must be committed. Note the correction the reviewer forced: a digest computed from the
values the insert loop bound compares only against itself, so the seal-time check **must re-read the
flushed shard**.

**One measurement-reporting shape.** A generic sections map on `BenchmarkReportTask`, a `renderTable`
that no longer short-circuits the other sections when `scenarios` is empty, and an entry point that
writes a results directory without booting servers. Every measure task declares one output file and
writes an explicit `{"measured": false, "reason": ...}` when skipped, so a stale artefact can never be
copied under a new commit.

---

## The traps

The full plans and critiques are not committed; these are the findings that change what you build.

**Compaction.** Shard ids are allocated by the compactor but only reserved at publish, so a seal
during a merge steals the id and clobbers the in-flight file. A superseded file cannot be deleted
while a cursor holds it — on Windows the delete fails, on POSIX it unlinks under the reader, which is
worse because the bug then behaves differently per platform; hence refcounting, swept at the next
pass and unconditionally at `open()`. `ShardKeys.timestampOf` uses a logical shift where `key()` uses
an arithmetic one, so re-basing is lossless only for `ts >= base`: a merged shard's `base_ts` must be
recomputed from the rows actually copied. Use a plain `INSERT` and treat `SQLITE_CONSTRAINT` as a
quarantine event; `INSERT OR IGNORE` would silently drop rows and break the multiset guarantee.

**Cursor identity, the sharpest question in the milestone.** A stored `CursorPosition` is absolute and
names no file, so a lossless re-partitioning of rows over files leaves every stored cursor valid and
ADR-0015's premise survives — but only given exact row-count preservation, correct re-basing, and no
primary-key collisions. All three are ways to get it wrong quietly.

**Retention and purge.** Purge planned from a `shard_actor` table is empty for every shard sealed
before the format bump, so an erasure request against existing history would report zero rows and
delete nothing. Purge must also touch the hot window, which carries the same actor column. There is a
crash window between the manifest swap and a file move that turns a rollback into a silent no-op — a
file move cannot join a SQL transaction. And `ActorDictionary.forget()` as proposed reuses the erased
player's id after the next restart, because `nextId` is recomputed from the surviving lines.

**Verify and quarantine (returned `broken`).** Recording a gap when a seal fails is wrong and
permanent: the rows are still in the hot window, so nothing was lost, and a gap is unretractable.
`UNVERIFIABLE` was defined both as a persisted state and as a condition under which shards stay live;
those are mutually exclusive. `VACUUM INTO` does not work from a `query_only` connection. A seal-time
`CORRUPT` currently reaches the operator as a flush *timeout*, because `flushAndSeal` relabels every
exception `CONTENDED`.

**Blobs are deferred to M4.** The design had no journal frame for them, no read path (the scan
statement selects eight columns and `MergingCursor` deliberately hides which shard a row came from,
so `blob(shardId, blobId)` has no caller that knows `shardId`), and its `(slot, sidecarSeq)` pairing
would have built payload identity on the slot id that ADR-0012's known defect can duplicate.

**SPIKE-2.** The "closed SQL allowlist" cannot be closed, because SQLite cannot bind identifiers:
four of nine queries concatenate operator-supplied names, against a database
[provenance.md](../decisions/provenance.md) records as holding real IP addresses. Resolve identifiers
against `sqlite_schema` and `PRAGMA table_info` and record the keys, not rendered SQL. Bytes-per-row
on the Trace side is a function of the shard's time span, because `k` folds the timestamp into a
primary-key column, so a workload that seals once over the whole span is not comparable to production
sealing every 60 s.

---

## Carried debt: what M3 takes and what it leaves

**Resume after a real crash — M3 must absorb it.** M3 adds three new ways to invalidate a resume that
did not exist in M2: compaction moves rows between files under a stored cursor, retention advances a
horizon past a paused operation's window, and quarantine inserts a gap inside it. Two reviewers hit
the last two independently. Shipping those over a resume path whose crash behaviour has never been
tested converts one acknowledged gap into three untested interactions.

**The contended-chunk path — leave it.** Nothing in M3 touches the region-thread apply path.
Absorbing it spends the one serialised server slot on a question no M3 change affects.

**ADR-0012's thirty-third capture thread — do not fix it in M3, but make it loud.** It is silent loss,
and everything M3 builds is machinery for making loss visible, which is a real argument for fixing it
first. Against: it is a capture-transport defect on the hottest path in the plugin, it lands squarely
on gate P1's allocation claim, and it needs its own allocation measurement and crash gate before
anyone should believe a fix. Decisively, **M3 does not widen the exposure** — `writeShard` already
does a plain `INSERT`, so a duplicate already fails at seal, and the one M3 feature that would have
widened it, blob sidecar pairing, is deferred anyway. So: route the seal-time `SQLITE_CONSTRAINT` to a
named condition with a counter instead of a generic error that `flushAndSeal` relabels; name the
defect as the merge's first untested precondition; and block the M4 blob pairing on it explicitly.
That turns a silent bug into a counted one for a few hours of work, without pretending it is fixed.

---

## Honesty ledger

Written before the work, so it cannot be written to flatter it afterwards.

**Would genuinely be proven:** a failing shard leaves the read set and a `QUARANTINE` gap appears in
the same commit, asserted by a contract test every backend inherits and an atomicity test that
injects a failure into the gap insert; that `PRAGMA integrity_check` is insufficient for payload
corruption, pinned by a bit-flip test whose control arm asserts some flips *are* reported "ok", so the
digest cannot later be deleted as redundant; that a merge preserves the row multiset; that a cursor
stored before a merge resumes to an identical suffix after it; that a superseded file is not unlinked
while a cursor holds it; that a rollback across a quarantined or expired window is refused; and that
the density reader emits no planted canary usernames, UUIDs, IPs or coordinates.

**Would read as proven and would not be:**

- **No shard window number.** 60 s is a private constant with nothing behind it. The sweep that would
  choose it is specified, not run.
- **No density figure until the task is committed and run.** The observation in provenance.md stays
  unpublishable until then.
- **Nothing is measured on Folia.** Every claim about maintenance not disturbing region threads is a
  design statement. The M2 both-platforms pattern has not been applied to any of this.
- **The crash rig has never been pointed at a merge, a sweep or a purge.**
- **Seal cost increases by an unmeasured amount**, because the seal-time verify re-reads the shard,
  on the path a rollback blocks on.
- **The refcount is correct by convention**, holding only while `scan()` is the sole path that opens
  a shard connection. A later second path leaks, and the symptom is disk growth, not a red test.
- **Global uniqueness of `(worldId, chunkKey, ts, seq)`** is argued in ADR-0011, contradicted in
  ADR-0012, and tested nowhere across shards.
- **Erasure is pseudonymisation.** A dense actor id still groups one person's actions, the journal
  still holds the records, and the audit row deliberately outlives the identity it records erasing.
  Whether that satisfies a regulator is not a question this plugin answers.
- **The maintenance pass is never asserted to fire.** A scheduled task that silently never runs is
  this milestone's worst failure mode; the heartbeat and status line are mitigations, not proof.
- **Real corruption is not bit flips.** Stale pages from a failing drive, a lost extent, a snapshot
  restored mid-write, a shard on a network mount — none is modelled.
- **`verifyModuleGraph` does not prove storage is Bukkit-free.** It compares project paths only, so a
  `compileOnly(libs.paper.api)` in a storage module would pass it. Either add a real check or stop
  citing it as evidence.

## Two corrections this review turned up

**ADR-0005 says the configuration system was implemented in M2. It was not** — there is no
`trace-paper/src/main/resources`, no template and no loader. The header is wrong and should be
corrected whether or not retention ships. Retention cannot ship without configuration: a retention
default that cannot be turned off is not acceptable for the only irreversible operation in the
plugin.

**`gap.id` is a plain rowid alias** while `rollback_op.id` is `AUTOINCREMENT`. Make the former
`AUTOINCREMENT` too, so an audit row cannot silently re-point at a different gap after a deletion.
