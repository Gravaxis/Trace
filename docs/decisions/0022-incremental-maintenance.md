# ADR-0022: Incremental unpublished work and scheduled WAL checkpoints

* Status: implemented; final measurement and server regression pending
* Date: 2026-09-21
* Milestone: M3

ADR-0021's abort/restart rewrite remains available for explicit atomic operations.
Scheduled maintenance instead calls EventStore.maintain. ShardWork retains keyset
cursors and digests while yielding to the consumer. Input size no longer needs to
fit one step. Compaction selects a pair; retention selects one eligible sealed
shard. Each seal freezes a bounded hot-row prefix; later appends remain hot.
Retention publishes replay exclusions and refusal gaps with the shard swap. Those
exclusions also hide matching blob references atomically; explicit collectBlobs
reclaims their physical reference rows and unreferenced payloads. Publication does
not execute an unbounded reference deletion. Other still-physical expired shards
in that conservative gap window are refused until later passes reclaim them.
Scheduled sealing replaces StoreConsumer's unbounded periodic seal. Explicit
flush-and-seal still completes the entire hot window for a caller's read fence.

Unpublished outputs have unique filenames. Only publication assigns a shard id.
All input content and counts are checked; output is forced and independently
re-read. The canonical row encoding feeds both ordered SHA-256 and an additive
modulo-2^128 sum of per-row SHA-256 prefixes plus count. Plain INSERT rejects
duplicate identities. The inherited contract checks complete fields and stored
suffixes across incremental merges; RowDigestTest rejects paired duplicates and
same-count payload substitutions. The sum is not a proof against malicious edits.

No manifest transaction or reader snapshot survives a yield. Explicit sealing,
rewrite or quarantine cancels unpublished work before mutation, so an old job
cannot resurrect removed rows. Process death discards unfinished work at startup;
live-process yielding preserves it. This is not a persistent maintenance resume
protocol. Inputs remain authoritative until the publication transaction commits.

The consumer cycles compaction, content verification, opt-in retention, checkpoint
and sealing. It drains between calls. A failing phase advances so a bad merge
cannot indefinitely starve verification. Cancellation, progress, deferral,
unverified legacy content and quarantine have distinct outcomes. Content verify
uses bounded keyset pages of sealed files, not a long manifest snapshot; full
explicit verify retains SQLite structure and blob checking. Full integrity_check
and blob audits are not claimed to be incremental.

Checkpoints use TRUNCATE with busy_timeout=0 and inspect the returned busy flag.
A held reader yields DEFERRED; the scheduler retries. The unit gate holds a real
snapshot, asserts deferral, then releases it and asserts the WAL is truncated.
Long-lived readers and overloaded capture can defer maintenance: no finite WAL
size or completion latency is promised under those conditions.

Budgets are cooperative row-page boundaries, not hard deadlines: SQLite page I/O,
force, commit, checkpoint and publication cannot be preempted safely by Java.
Settings are safety choices, not measured optimums. Power loss, native-memory
bounds, failing disks and arbitrary instruction interruption remain unproven.
Process-kill gates cover an intermediate step, forced output and committed output.
