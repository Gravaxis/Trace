# Bounded payload queue — plan and self-review, 2026-09-21

Owner direction: finish the real-client gate, then bound M4 payload queues. The
client gate and evidence are committed. This slice builds the queue prerequisite
for the atomic capture pipeline; it does not enable payload listeners ahead of it.

## File-level plan

Add `trace-core/src/main/java/in/gravaxis/trace/core/capture/BoundedPayloadQueue.java`.
Existing `MappedEventRing.offer/drain`, `CaptureLoss.record` and
`CaptureService.publish` were inspected. The new queue owns a mapped file with
fixed entry count and payload limit supplied at startup. An entry holds the four
event words, codec version, exact payload length and copied payload bytes. Release
publication happens after the whole entry is written; acquire read happens before
copying to a caller-owned buffer. No queue growth, wait, file force, serialization
or dictionary work occurs in offer. Mapped-page faults are not a hard-time bound.

`peek` keeps the entry pending; `acknowledge(ticket)` releases it only after the
future journal consumer has safely handed it on. Tickets are monotonic and survive
reopen. Rejection distinguishes FULL, OVERSIZED, WRONG_PRODUCER and invalid input;
persistent loss bounds widen before their count advances. The caller supplies the
conservative capture window explicitly. A second mapping is refused using a file
lock, and a second producer cannot write the first producer's slots. Single
consumer ownership is enforced separately. Setup/close are blocking startup or
shutdown operations, never tick operations.

Add `BoundedPayloadQueueTest` under trace-core tests, and a child-process fixture
under trace-core testFixtures for a real kill without close. Test exact content,
all rejection branches, read-without-ack retry, too-small read buffer, stale ack,
reuse, producer contention, independent producer/consumer publication and reopen.
The killed process publishes an entry and overflows the queue, signals readiness,
then is forcibly terminated; reopen must recover the entry and rejected-event
bounds. Close all mappings so Windows temporary-directory cleanup is meaningful.

Extend the existing benchmark evidence infrastructure with a scoped queue test
collector. Raw results belong to the committed source that ran them. No queue
throughput, allocation, latency, optimal capacity or density claim is planned.

## Self-attack before code

* Two separate event/payload queues can diverge under overflow: one entry carries
  both and publishes them together.
* A drain that advances before journalling loses retryability: explicit peek/ack,
  with stale acknowledgement rejected, is required.
* Slot reuse can bind old events to new bytes: a lifetime ticket is distinct from
  the masked array slot, never reset on reopen, and exhaustion refuses reuse.
* A second file mapping bypasses Java owner fields: hold an exclusive file lock
  for the mapping lifetime. Process death releases it.
* A rejected event may predate startup: record the supplied lower and upper
  bounds, not just now or the marker creation time.
* A small consumer buffer can be mistaken for an empty queue: TOO_SMALL is a
  distinct result and cannot acknowledge an entry.
* Corrupt headers must not silently reinitialize history: refuse invalid existing
  magic/version/configuration/length/cursor bounds. Detect malformed entry lengths
  on read. This is not payload authentication or power-loss integrity.
* Passing round-trip tests says nothing about overload: force full/oversized/wrong
  owner branches and check exact counts and bounds after a real process kill.

## Integration boundary and limits

This queue is not yet wired into CaptureService or StoreConsumer. Existing block
capture remains on its current bounded rings. Atomic event/payload journalling,
TraceRuntime recovery and loss-to-GapRecord integration must land before a real
payload capture producer can use it. An available queue primitive alone does not
establish an end-to-end payload durability gate. That dependency stays explicit.

The process-kill test is not power-loss proof, and fixed allocation is not a
measured latency or native-memory bound. OS mapping stalls, disk failure,
malicious edits and arbitrary instruction crashes remain unproven. There is no
spill, eviction of accepted entries, blocking producer fallback or unbounded
overflow collection. Allocation and performance are **not measured** for this
new path; existing capture allocation gates retain their existing scope.

## Implementation review

`BoundedPayloadQueue` now implements the planned transport. Review found that
testing file length alone to identify a new queue would silently reset an existing
empty file. The regression failed before the fix: creation now uses `CREATE_NEW`,
and existing files always take the validation path. Tests also corrupt the cursor
and entry length and require refusal without acknowledgement. Persistent rejection
counter offsets are explicit rather than derived from enum order.

Run `./gradlew :benchmarks:payloadQueueEvidence` from the committed clean source.
It always reruns `:trace-core:payloadQueueTest`, checks each required test name and
the absence of skips/failures, and writes raw XML plus source provenance. It boots
no server. The forced-kill case signals only after an accepted entry and a FULL
rejection, then requires a nonzero child exit and exact recovery on repeated reopen.
It does not claim recovery from a kill partway through publication or rejection.
