# ADR-0016: Single-database publication and explicit storage maintenance

* Status: explicit APIs implemented and tested; operational completion outstanding
* Date: 2026-09-21
* Milestone: M3

## Decision

Format 3 places hot rows in manifest.db. SQLite WAL does not atomically commit
across attached databases; the earlier comments asserting otherwise were wrong.
Migration copies the legacy hot table and advances the format in one transaction.
The old hot.db is retained, never read again by format 3, and must be accounted for
as legacy disk overhead. Newer formats are refused before schema mutation.

Compaction constructs an unpublished replacement, re-reads it, then retires its
inputs and publishes its output in one manifest transaction. A scan holds a
manifest snapshot and a read lease until closed. Retired files are swept only when
no scans remain; startup retries the sweep. Serialized maintenance currently holds
the store monitor for the rewrite. Its effect on consumer lag is **not measured**.

Rows are encoded canonically using absolute timestamps and all stored event fields
for SHA-256. A new seal is checked against its intended row digest by re-reading
the file after force. Compaction tests compare complete row sequences, including
multiplicity and both scan directions; duplicate identities fail plain INSERT.
Legacy shards with no expected digest remain explicitly unverified rather than
receiving an invented historical integrity claim.

Verification checks SQLite structure, digest when available, counts and timestamp
bounds. Quarantine removes the shard from the read set, records a QUARANTINE gap
and appends audit metadata in one transaction. The physical file is retained.
Failure to seal unpublished output does not create a loss gap: hot rows remain.

Retention and actor/time purge are explicit storage API calls. No automatic
retention default or periodic maintenance is enabled. They replace filtered sealed
history, remove matching hot rows and blob references, persist replay exclusions,
and record refusal gaps atomically. Destructive maintenance defers while a scan is
open. Gap checks in scan acquisition close the gap-check/purge race. Gaps are
conservative across worlds and actors; this can refuse unrelated rollback work.
Repeated removal preserves remaining row content, but can still rewrite files and
append audit/gap metadata. This is not an efficient no-op guarantee.

Purge does not erase actor dictionaries, journals, old hot.db, backups, quarantined
files or filesystem free space. Tombstones prevent replay into the live store;
they do not constitute secure erasure. No regulatory claim follows.

## Proof and limits

`StorageMigrationTest`, `ShardMaintenanceTest`, and `StorageCrashTest` cover the
implemented branches. The latter kills actual child JVMs before and after a
rewrite's publication, then reopens twice. A trigger that aborts gap insertion
asserts quarantine leaves both live state and gaps unchanged. Payload mutation via
valid SQL asserts integrity_check succeeds while the stored digest fails.

These do not simulate power loss, failing disks, malicious manifest modifications,
or arbitrary interruptions at every instruction. Migration interruption, bounded
maintenance budgets/cancellation, automatic scheduling/configuration, and large
dataset memory/latency gates are not established. The plan's additive multiset
accumulator is not implemented; complete-field oracle tests and row counts are
the current preservation checks, alongside seal digests.

## Migration follow-up proof, 2026-09-21

StorageCrashTest kills migration after copying legacy rows inside the transaction
and after commit. It checks the old/new format before recovery, then exact row
fields and the applied watermark after two reopens. These two boundaries now have
process-kill proof; arbitrary instruction interruption and power loss remain open.
