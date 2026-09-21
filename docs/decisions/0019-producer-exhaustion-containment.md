# ADR-0019: Producer exhaustion rejects with persisted loss bounds

* Status: containment implemented; allocation and server regression gates pass
* Date: 2026-09-21
* Milestone: M3 debt absorption

`CaptureService.newProducer` no longer maps the final SPSC ring for another
producer. An excess thread receives no producer and its events take a counted
`droppedNoSlot` branch. It never stages into or writes another producer's arrays,
ring or clock. This contains silent overwrite; it does not implement reclamation,
the ADR-0011 shared fallback, or spill transport.

`CaptureLoss` keeps conservative loss bounds and a counter in a mapped file using
atomic accesses. The bound is published before the counter. It covers producer
exhaustion, ring/clock rejection and out-of-range positions. StoreConsumer records
the bounds rather than assuming the time of a drop equals the time of its original
capture. Startup reads the persistent marker independently of journal clean-close.

The lower bound currently starts when the marker was created; it is not reset.
Later losses can therefore bridge otherwise complete history, and restart can
write a duplicate conservative gap. This is deliberately broad and may refuse
more rollbacks than necessary. Narrow crash-safe acknowledgement/reset needs a
separate protocol; pretending this is an exact lost-event interval would be false.
Mapped contents cover process death, not a claimed power-loss guarantee.

The concurrency test runs more threads than slots, asserts exact accepted and
rejected branches, distinct ring count, every accepted record, and persisted loss
bounds through another mapping. This is not a crash test of slot exhaustion. The
normal and cold exact allocation tests cover publishing/rejecting unchanged events,
not every first-thread initialization or the newly added exhaustion branch. JMH
results and exact measurement windows are recorded in
`benchmarks/results/m3-validation/2026-09-21/allocation/`. Both instruments pass;
their scope is unchanged, and they do not measure first-use or exhaustion costs.

This supersedes ADR-0012's statement that excess threads silently share the final
slot, but leaves slot reclamation and spill work outstanding.

## Follow-up proof, 2026-09-21

StorageCrashTest now kills a child process after real CaptureService exhausts its
slots and rejects an event, without closing its mapped loss marker. Two reopens
use the same CaptureRecovery.recordLoss path as TraceRuntime startup and assert
OVERFLOW count and bounds covering the rejected event. No generic crash gap is
created in this test. The earlier lack of an exhaustion crash test is resolved
for this process-kill branch, not for power loss or partial marker instructions.
