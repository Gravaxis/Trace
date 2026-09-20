# ADR-0013: Journal first, database second — and a frame that cannot be misread

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M2

## Context

L7 requires every accepted event to reach an append-only journal, forced within a bounded window
(default 200 ms), *before* it reaches the shard store, and to be replayed on restart. The honest
statement of what that buys, which belongs in the README rather than a footnote: with SQLite in WAL
mode at `synchronous=NORMAL`, a power cut can still roll back the last transaction, so a machine
losing power can cost up to the unforced window. That is the right trade for a grief log and the
wrong one for a ledger.

Adversarial review found two ways a journal that looks correct is not:

* a CRC covering only part of the frame header lets a bit flip in an uncovered field route records
  to the wrong window undetected;
* recycled segments still contain old frames with valid magic numbers and valid CRCs, so a replay
  that walks past the true tail can resurrect them.

## Decision

**Segments.** Fixed-size (64 MiB) preallocated files, recycled by rename so a force never needs a
metadata update. The logical sequence number (LSN) is the byte offset, which makes every watermark a
single comparable number.

**Frames.** Header plus payload, 8-byte aligned. The header carries magic, type, flags, payload
length, record count, the frame's own LSN, the minimum and maximum capture timestamp in the frame, a
**low-water mark** (the oldest unjournalled capture timestamp across all slots at write time, used
for gap bounds — ADR-0012), a **segment salt** stamped when the segment was opened, and a CRC32C.

Two rules make the tail unambiguous:

1. the CRC covers the **entire** header (except the CRC field itself) plus the payload;
2. `frame.lsn` must equal the position the frame was read from.

The log ends at the first frame that fails either of them. A recycled segment's stale frames fail
rule two, because the LSN is the absolute position and the segment's base is in its filename, so
they cannot agree anywhere else.

The salt identifies **the run that wrote the frame**. It is read and reported (`ReplaySummary.runs`)
but it is not a validity rule.

### Correction, 2026-09-20

This section originally carried a third rule — *`frame.salt` must equal the segment's current salt* —
with "current" implemented as whatever salt the segment's first frame carried. A segment stays
current across a restart, so the second run's frames carried a different salt, and:

* the reader stopped at the first frame of the second run, reporting the log as ending there;
* the writer, which resumes at the reader's end position, then **truncated every frame the previous
  runs had written past that point**;
* the store's applied watermark had already advanced past those positions, so every frame the new
  run wrote was at or below it and was discarded as an already-applied replay.

The visible symptom was a server that captured, journalled and counted 36 records and then rolled
back nothing at all, with no error anywhere. Found by the M2 integration scenario on its second run
against a data directory left over from the first; a unit test in `JournalRoundTripTest` had
asserted the truncation as intended behaviour, with a comment explaining why it was fine.

Two changes, both tested: a salt change is a restart boundary rather than end-of-log, and
`JournalWriter.open` takes the store's applied watermark as a floor — if the log does not already
reach past it, a new segment is started above it, so journal positions can never move backwards
relative to what the store has applied. `TraceRuntime` refuses to start if they ever do.

Frame types: `EVENTS`, `DICT` (dictionary additions, always journalled before the first event that
uses the id), `BLOB`, `GAP`, `CHECKPOINT`, `CLEAN_CLOSE`, `PAD`.

**Force policy.** A dedicated platform thread forces when the oldest unforced record approaches the
window, publishes `forcedLsn`, and measures its own fsync latency at startup, warning if the p99
exceeds half the window. Forcing is never done on a tick thread or inside a batch commit.

**Watermarks and recovery order.** `writtenLsn` survives `kill -9` (the bytes are in the page
cache); `forcedLsn` survives power loss; each hot window stores the `appliedLsn` it has consumed, in
the same transaction as the rows. Restart then:

1. take the data-directory lock (single writer per data directory — L15);
2. clean the manifest: delete orphan shard files and hot files already superseded by a sealed shard;
3. replay the journal from the lowest live `appliedLsn`, routing each record to its window and
   skipping anything already applied there (per record, per destination — one frame can span
   windows);
4. replay ring and spill contents newer than each slot's recovered clock floor;
5. reset the rings, seed the clocks, and, if the shutdown was unclean or ring contents cannot be
   trusted, write the `CRASH_WINDOW` gap;
6. resume any operation left running (ADR-0015).

Every step is idempotent, so a crash during recovery is just another restart.

**Journal segments are recycled only below `min(forcedLsn, dictLsn, every open window's durable
LSN)`,** and a window's durable LSN advances only after a checkpoint that actually completed — a
`PASSIVE` checkpoint blocked by a reader does nothing, and treating it as success would let the
journal be recycled ahead of the data.

### The crash gap's lower bound, 2026-09-20

The frame header has always carried a low-water mark, and until now nothing read it. The gap a
restart records after an unclean shutdown started at the newest capture timestamp the journal
managed to write.

That is the wrong bound, and wrong in the dangerous direction. Capture stages per thread and commits
at tick end, so a region thread can still be holding a record older than everything another thread
has already journalled. A gap beginning at the newest journalled capture would then start *after* an
event it has to cover, and a rollback across that window would run believing the history complete —
which is worse than having no gap at all, because a gap at least produces an honest refusal.

So the mark now means what ADR-0012 said it meant: the oldest capture time that could still be
missing. The capture layer tracks the oldest record staged on each producer, the consumer writes the
minimum of that and the frame's own oldest record into every frame, the reader surfaces the last
one, and recovery starts the gap there. Adding it cost one volatile write per tick per producer, and
the allocation gate still reads zero.

Proven by the durability gate (`crashJournalPaper`, `crashJournalFolia`): a server is killed with
SIGKILL while Trace is capturing, and after the restart every event the previous run wrote down and
Trace does not have must fall inside a recorded gap. The gate also fails a set of iterations in
which nothing was ever lost, because a verification with nothing to verify is not a pass.

## Consequences

* Two writes per event (journal, then store). That is the cost of not losing the last few seconds of
  a grief incident, and the journal write is sequential and batched.
* The disk layout has more moving parts than "a database file": segments, rings, spills, shards, a
  manifest. The operations documentation has to explain each one, and `/trace verify` has to check
  them.
* Recovery is testable without a server: frames are produced and consumed by `trace-core`, so the
  torn-tail, bit-flip and recycled-segment cases are unit and property tests, with the crash rig
  covering the real thing on top.

## Alternatives considered

* **Rely on SQLite's WAL alone.** Simpler, but the window between "the tick thread accepted the
  event" and "SQLite committed it" is exactly what a crash eats, and batching to keep up makes that
  window bigger.
* **`synchronous=FULL`.** Durable against power loss, at a cost per commit that a logger doing tens
  of thousands of events a second cannot pay. The journal plus `NORMAL` is the compromise, stated
  plainly rather than hidden.
