# M4 completion work — 2026-09-22

M4 is incomplete. The accepted execution order in `m4-capture-plan.md` still
applies. This document records the next implementation decisions before code.

## Foundation: trustworthy tick observations

Verified files: core `capture/CaptureService.java` (`Producer.stage`, `confirm`,
`flushUnconfirmed`, `publish`), `capture/CaptureLoss.java` (`record`),
`record/EventRecords.java` (packed field limits); paper
`capture/BlockCaptureListener.java` (`stateAt`); benchmarks `CaptureHarness` and
`ExactAllocationTest`. Existing tests explicitly require publishing unconfirmed
before=after rows, following ADR-0014's original fallback. That fallback conflicts
with the working agreement's log-changes-only and every-loss-is-gapped rules.

Change unconfirmed observations into counted persistent loss, not rows. Preserve
the public unconfirmed counter as a count of observations lost without confirmation.
Distinguish staging exhaustion from an unavailable post-state and explicit flush.
Validate world, state, cause and kind widths before encoding, including the state
returned by the reader. Actor integers represent the entire unsigned packed word.

Coalesce repeated observations of a world/position within one producer's tick
using a bounded primitive hash index. Keep the first before-state and capture
time; read the final state once. Identical attribution yields one net row; returning
to the first state yields none. Conflicting actor/cause/kind cannot truthfully
describe one net change: gap that changed position rather than invent attribution.
Count observations separately from emitted rows. This is not sub-tick history.

Proof: exact row fields and counters for same-position sequences, round trips,
world separation, index reuse, hash collisions, conflicting attribution, staging
exhaustion, missing and out-of-range reads, and persistent loss bounds. Extend
normal and cold allocation gates to the new reached branches. Run the real-client
Paper/Folia regression serially. These gates do not prove full block properties,
NBT, mobile ownership, arbitrary asynchronous producers, or power-loss survival.

## Remaining implementation sequence

* Dictionary publication: `ActorDictionary.register/save`,
  `WorldDictionary.register/save`, `BlockStateDictionary.load`, and runtime startup
  must never expose nondurable ids. Replace tick-thread identity persistence with
  ordered async publication and gap captures whose dependencies are unavailable.
  Test failed publication, restart identity and real client registration. Merely
  moving a write to another callback does not prove ordering or crash durability.
* Full states: preserve legacy material ids and add explicitly versioned full-state
  identities. Isolate verified internal state access behind ADR-0007 probes; prove
  property-only changes on both platforms and rollback refusal for unsupported
  content before enabling broader causes. New APIs need javap on both pins.
* Block causes: implement the matrix in the original plan with natural triggers,
  cancelled/no-op branches and exact rows. Inventory additional event families
  before calling the matrix complete. Synthetic events alone are insufficient.
* Block entities and containers: attach complete versioned before/after snapshots
  through `PayloadQueues`/`PayloadHandoff`/`appendCaptured`, sharing producer clocks
  with ordinary records. Test actual inventory changes, ownership, oversized or
  unavailable snapshots, replay and maintenance. Preserve rollback preflight
  refusal until restoration has its own world/item proof.
* Entities and sessions: persist UUID/run identities, observe on owning threads,
  distinguish removal from unload and leave crash-open sessions open. Test natural
  lifecycle and real reconnect, including durable identities and unsupported-kind
  rollback refusal. Do not collect network addresses. Mobile scheduling must stay
  within the repository's explicit scheduler constraints.

The detailed file identifiers and obstacles for those subsystems remain in the
original plan; each slice needs its own implementation-level review and verified
API inventory before code. No pending row receives supported status from a plan.

## Self-attack before implementation

* A linear duplicate search makes staging depend quadratically on a burst. Use a
  fixed primitive index; probe until an empty slot and clear the index each tick.
  Runtime cost is **not measured**.
* Coalescing only by coordinates mixes worlds. Include world id and compare all
  coordinates after hashing. A collision must not become equality.
* Keeping the last before-state invents history for a round trip. Retain the first.
* Keeping the first actor for a mixed-actor change misattributes the net result.
  Refuse ambiguous changed observations with persistent loss bounds.
* Overflow after index lookup must still allow a duplicate of an already staged
  position. Otherwise a full buffer loses observations it could safely combine.
* A missing post-state cannot be represented by before=after. Count and gap it;
  update the contradictory fallback in a superseding ADR, not a silent test edit.
* Bit masks hide invalid ids. Validate producer and reader fields before packing.
* Tests can pass on the unchanged/drop branch while claiming publication. Assert
  published, coalesced, rejected and each specific loss counter alongside rows.
* Existing allocation evidence is for older bytecode. Generate new committed raw
  evidence after a clean source commit; do not relabel or edit an older run.

## Regression finding

The first serial resume regression refused with a capture gap. The playerless
fixture loaded chunks transiently but did not retain them through tick end. With
the new availability precheck this can no longer be hidden by reloading during a
read. The fixture now holds plugin chunk tickets, asserts ownership and checks
the confirmation counters before rollback. The production loss behavior is
unchanged. The targeted Paper retest passed; the full clean-source matrix still
has to pass before this slice's evidence is complete.
