# ADR-0011: The 32-byte event record, and how sequence numbers stay unique

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M2

## Context

L5 fixes the capture record at four longs claimed from a preallocated ring: position, state,
actor and time, sequence and sidecar. L1 fixes the sealed-shard primary key at
`(worldId, mortonChunkKey, ts, seq)`, which means the key must be unique for every event the
server can produce — including two events at the same position in the same millisecond, and events
captured concurrently by several Folia region threads.

Ranges that constrain the layout, verified against the current game: the world border caps block
coordinates at ±29,999,984 (26 signed bits covers ±33.5 M), and datapack-legal build heights run
−2032…2031 (12 signed bits covers ±2048).

## Decision

**Transient record — ring, spill, journal and hot pages (32 bytes).** Times here are relative to a
fixed epoch so they fit; sealed storage keeps absolute milliseconds.

| Word | Bits | Field |
|---|---|---|
| L0 | 63..38 / 37..12 / 11..0 | `x` s26, `z` s26, `y` s12 |
| L1 | 63..40 / 39..16 / 15..0 | `beforeState` u24, `afterState` u24, `aux` u16 |
| L2 | 63..24 / 23..0 | `tRel` u40 (ms since the Trace epoch), `actorLo` u24 |
| L3 | 63..48 / 47..36 / 35..28 / 27..24 / 23..16 / 15..0 | `seq` u16, `worldId` u12, `cause` u8, `kind` u4, `actorHi` u8, `sidecar` u16 |

Out-of-range input is never truncated: it increments `rejectedOutOfRange`, emits a gap marker for
that moment, and is dropped loudly.

**Sequence numbers.** `seq = counter10 << 6 | slot6`, where `slot` identifies the producer ring and
`counter` is a per-slot counter within the millisecond:

* a slot has exactly one owning thread at a time, so `(slot, ts, counter)` is unique;
* therefore `(worldId, chunkKey, ts, seq)` is unique, which is what the primary key needs;
* when a slot exhausts its 1024 counters inside one millisecond, it does **not** borrow from the
  future indefinitely. It may advance its logical clock by at most `maxLogicalDriftMs` (default 2,
  a fraction of a tick); beyond that the producer moves to the overflow path and the event is
  spilled rather than mis-ordered. Unbounded borrowing would let a later event on an undrifted
  thread carry a smaller timestamp than an earlier one, inverting history.
* `hlcDriftMs` is a reported metric with an alarm, and rollback time ranges are widened by the
  observed maximum drift before gap checks are evaluated.

On restart each slot's clock starts at `max(now, highest recovered value for that slot)`, and a
reaped slot's floor is inherited by its next owner, so replay deduplication stays sound.

**Slot allocation.** 0–55 producer threads, 56 a lock-guarded shared fallback (virtual or unknown
threads), 57 consumer-synthesised records (gap markers, retro-tagging), 58 the importer, 59 the
rollback engine's own writes, 60–63 reserved.

**Sealed storage** widens what the transient form packs: state ids become u32, time becomes an
absolute u48, and the physical key column is `k = (ts − shardBaseTs) << 16 | seq`, which preserves
the ordering the primary key declares while keeping the row narrow.

## Consequences

* The record is fixed-width, so a ring slot is an index rather than an allocation, and the encoder
  has no branches that allocate. Gate P1 measures exactly this path.
* 4,096 events per millisecond per thread is the ceiling before overflow. A single FAWE-scale burst
  exceeds that easily, which is precisely why bulk edits become section patches (L3) rather than
  rows; the overflow path exists so that the row path degrades honestly instead of lying about
  ordering.
* Block-state ids are Trace's own dense ids from a persisted dictionary keyed by the explicit state
  string plus data version — never the server's registry index, which is not stable across
  versions. u24 allows 16.7 M distinct states; the current game has far fewer.

## Alternatives considered

* **A global 64-bit sequence from an atomic counter.** Simpler, but a single contended cache line
  across 16 region threads on the hottest path in the plugin, and it would make the key wider.
* **Per-millisecond counter with no slot bits.** Needs coordination between producers; the slot bits
  give uniqueness for free from a fact already known to each thread.
* **Truncating out-of-range coordinates.** Rejected outright: silently writing a wrong position is
  the kind of defect this project exists to avoid.
