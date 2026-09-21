# ADR-0012: One ring per producer thread, a spill file, and a gap marker that always covers the loss

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M2

## Context

L6 puts one ring per producer *thread* rather than per Folia region, because regions merge and split
at runtime and expose no stable handle, while thread identity is stable and per-thread ordering is a
superset of per-region ordering. L8 says back-pressure must never block the tick thread, and that
anything dropped must be recorded as an explicit gap — a rollback whose window contains a gap
refuses rather than producing a partly-correct world.

Adversarial review of the first design found the hole that matters: a gap marker is only useful if
it is guaranteed to *cover* what was lost. Two ways it silently would not:

* with anonymous (heap or off-heap) rings, a `kill -9` loses everything not yet journalled, and the
  restart path had no reason to write any marker at all;
* a gap's lower bound taken from "the last journalled timestamp" can sit *after* events that were
  lost, because frames are ordered by write position while a lagging ring carries older timestamps.

## Decision

**Rings.** One single-producer/single-consumer ring per producer slot, backed by a memory-mapped
file under `data/rings/`. Mapped, not heap: a 512 KiB `long[]` is a humongous object on a 1 MiB G1
region, the GC never has to scan it, and — the reason that decides it — its contents survive the
death of the process, so a `kill -9` costs nothing that was published. Publication is a release
store of the tail after the four words are written; the consumer reads the tail with an acquire load
and advances the head only after the journal write returns. Head and tail sit on separate cache
lines.

**Finding a ring** is a `ThreadLocal` lookup; the slow path (a thread's first event) claims a
pre-mapped slot by CAS. Slots are reclaimed only when the owning thread is dead *and* its ring is
drained, and the reclaimed slot keeps its clock floor (ADR-0011).

**Spill.** When a ring is full the producer writes to a per-slot memory-mapped spill segment and
stays in spill mode until the spill drains, which preserves per-slot order. The tick thread never
waits and never allocates on either path.

**Gap markers.** Only when the spill is also full is an event dropped. Then:

* a monotonic `dropTotal` counter in the ring header is incremented (separate from the resettable
  counter `droppedCount()` exposes, so resetting the public counter cannot hide a drop);
* the consumer turns the increment into a `GAP` record covering `[lastKnownGoodTs − 1, max(now,
  the slot's clock) + 1]` — a range that can only widen, never narrow;
* the marker goes into the journal and the manifest, and a rate-limited warning goes to the console.

**Crash coverage.** On an unclean start, the recovery path emits a `CRASH_WINDOW` gap whenever ring
and spill contents cannot be trusted: a changed or unknown boot identity, a header generation or CRC
mismatch, or a ring configured with anonymous backing. Its lower bound comes from a low-water mark
carried in every journal frame — the oldest unjournalled capture timestamp across all slots at the
moment the frame was written — minus the maximum observed logical drift, so it cannot start after
something it must cover.

**The crash test asserts the coverage property, not just the loss shape**: every ground-truth event
that did not survive must fall inside a recorded gap. A rig that only checked "losses form a
suffix" would pass while L8 was being violated.

### Known defect: the thirty-third capture thread (2026-09-20)

**M3 update (2026-09-21):** the silent sharing described below is historical.
ADR-0019 replaces it with counted rejection and persisted conservative gap bounds.
Reclamation, spill and a shared fallback remain unimplemented. The description
below records the original defect rather than the current containment behavior.

There are 32 producer slots. A thread beyond that is given the last slot, and the code says so and
counts it (`slotsExhausted`). What it then does is wrong: it opens a *second* mapping of that slot's
ring file and builds a second clock for it, so two threads write into a ring that is single-producer
by construction. Two producers can compute the same slot index, and one record silently overwrites
another with no drop counted and therefore no gap. The comment claiming key uniqueness still holds
is false: two clocks with the same slot id can emit the same stamp.

This record already specifies the answer, and none of it exists yet: one ring per slot, claimed by
compare-and-set, with reclamation when a thread goes away. Until it does, a server with more than 32
distinct capture threads can lose records without saying so, and the durability property the crash
gate tests does not extend to that case. Written down here rather than left to be found, and not
papered over with a half-fix that would trade one silent failure for another.

## Consequences

* Trace writes ring and spill files during normal operation. They are fixed-size, preallocated and
  recycled; the operator sees them in the data directory and the documentation explains them.
* Windows has no stable boot identity available without native calls, so a crash there currently
  falls back to "cannot be trusted" and writes a `CRASH_WINDOW`. That is conservative in the right
  direction — it refuses rollbacks over a window it cannot vouch for — but it means a Windows host
  gets a refusal where Linux would not. Recorded as an open item; the fix is a boot-time identity
  read at startup.
* The failure ladder is honest: ring → spill → gap, with a metric at every rung and an alarm on the
  last.

## Alternatives considered

* **One ring per region.** What the intuition wants and the platform does not allow: regions have no
  stable identity across merges and splits.
* **A single MPSC ring for all producers.** One contended cache line on the hottest path, and it
  loses the per-slot ordering that makes replay deduplication simple.
* **Blocking the producer when full.** Trades a lost event for a stalled tick. For a logger that is
  the wrong way round, and it is exactly what the incumbent gets criticised for.
