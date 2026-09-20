# ADR-0014: Log changes, not attempts — confirm at the end of the tick

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M2

## Context

A user reported this against the incumbent: punching a lava block with a client mod produced a
stream of "TheMinecraft broke lava" entries in the log. The block was never broken — lava cannot be
broken by hand — but the server fired the event, and the logger recorded every one of them.

That is a whole class of defect, not a lava special case. A logger that records the *event* rather
than the *change* will also record:

* a break or place that the server itself rejects after the event fires;
* an edit another plugin reverts in the same tick;
* a client spamming an action that never takes effect.

The consequences are worse than noise. Inspecting a griefed area shows events that never happened;
a rollback fed those rows tries to restore a state the world was never in; and the "skipped, current
state does not match" counter fills up with entries that were never real, hiding the mismatches that
matter.

Paper's events mostly fire *before* the change, so the post-state cannot be read in the handler.
That is why this is a design decision rather than an `if`.

## Decision

**A change is recorded only if the world actually differs at the end of the tick in which it was
captured.**

1. Capture stages the record in a preallocated, per-thread buffer instead of publishing it straight
   to the ring. Staging costs no allocation: the buffer is claimed the same way a ring slot is.
2. At `ServerTickEndEvent` — which fires per region on Folia and once per tick on Paper, always on
   the thread that owns the region — Trace walks its own thread's staged records and reads the
   current state at each position:
   * current state equals the expected after-state: **confirmed**, published to the ring;
   * current state still equals the before-state: **nothing happened**, the record is dropped and
     `capture.rejectedUnchanged` is incremented;
   * current state is something else entirely: **something happened**, so the record is published.
     A third party changed the block after the event; that change has its own record, and dropping
     this one would lose the actor who started it.
3. If the staging buffer fills, or a thread stages records and never reaches a tick end (an async
   capture path, a region handed off mid-tick), the records are published **unconfirmed** and
   counted in `capture.unconfirmed`. Publishing an unconfirmed record is the safe direction: the
   rollback engine verifies the world against the recorded post-state before touching anything, so
   a stale record is skipped and counted there, whereas a dropped real record is gone.

## Consequences

* One extra block-state read per captured event, at tick end, on the thread that owns the chunk. No
  chunk loads: the chunk was just touched by the event that produced the record.
* Records become visible one tick later than the event. Nothing in the design depends on
  sub-tick visibility, and the timestamp is the capture time, not the confirmation time.
* An edit that a protection plugin reverts within the same tick is not recorded. That is the
  intended reading of "log changes, not attempts", and it is stated in the correctness contract so
  nobody is surprised by it. The revert itself, if it goes through the API, has its own record.
* `/trace status` reports both counters. A server with a high `rejectedUnchanged` is a server where
  something is spamming failing actions — which is useful to an administrator rather than noise in
  the log.

## Alternatives considered

* **Special-case the known failures** (fluids, unbreakable blocks, cancelled-after-monitor). Endless
  list, wrong shape, and it would not catch the next variant.
* **Confirm synchronously in the handler.** The state has not changed yet when the event fires;
  there is nothing to read.
* **Record everything and filter at query time.** Puts the cost on every lookup for ever, and still
  needs the post-state that was never captured.
