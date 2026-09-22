# M4 dictionary publication — plan and self-review, 2026-09-22

## Verified files and proposed changes

* `trace-paper/.../dictionary/ActorDictionary.java`: `register` currently changes
  its concurrent maps before `save`. Persist a proposed snapshot first, then
  publish reverse identity/name maps and finally the UUID-to-id lookup. Preserve
  the tab-separated format and reserved ids. Reject malformed/duplicate input.
* `dictionary/WorldDictionary.java`: `register(World)` likewise publishes before
  saving. Add UUID-only registration for worker use; preserve the legacy file
  format and validate the packed world-id bound. No live World crosses the queue.
* `dictionary/BlockStateDictionary.java`: `load/save` remains a startup operation,
  with strict input validation and the same forced atomic writer. This step does
  not reinterpret material ids or implement full block properties.
* Proposed `dictionary/DictionaryFile.java`: force a temporary file, atomically
  replace the final file, then return. Refuse an unavailable atomic move. Provide
  an instance fault probe for tests, not a global mutable test switch.
* Proposed `dictionary/DictionaryUpdates.java`: bounded concurrent registration
  requests, containing immutable UUID/name values. CAS admission and a concurrent
  queue avoid producer disk I/O and waiting on a writer lock. Keep the head request
  until persistence succeeds. Counters distinguish accepted, full and completed.
* `pipeline/StoreConsumer.java`: drain registration requests before capture queues;
  flush also drains pending registrations and refuses failure. Existing constructors
  remain usable by tests with an empty updater. Failed persistence leaves the head
  pending for retry and preserves unavailable ids.
* `runtime/TraceRuntime.java`: acquire the storage writer lock before dictionary
  writes; close it if dictionary startup fails. Wire the queue into consumer and
  listener. Snapshot world UUID before enqueueing later world registration.
* `capture/BlockCaptureListener.java`: `onJoin` only requests registration. Missing
  player/world identity takes a new allocation-free `CaptureService` loss branch,
  rather than publishing unknown identity or silently returning. Status includes
  registration backlog/rejection and dependency-loss counts.

## Ordering decision

This refines the original envelope proposal: dictionary dependencies are forced
before ids become visible, rather than carried inside every capture envelope.
An event can only reference a published id, so its ring/journal/store publication
follows dictionary persistence. Startup dictionaries follow the same ordering.
This preserves legacy dictionaries; it is not a dictionary migration into SQLite.

Registration requests are volatile and may disappear on process death. No event
can reference their unpublished ids; attempted captures while pending are gapped.
A queue-full registration does not itself assert a world change. Subsequent
captures without its identity are explicit loss. Rejoin/world registration retries
can recover availability; no unbounded retry list is introduced.

## Proof and obstacles

Unit tests will assert failure before replacement leaves old bytes and ids,
failure after replacement leaves ids unpublished until retry, exact reopen,
rename/retry semantics, duplicate/range/corrupt-file refusal, bounded admission,
retained failed head and concurrent queue admission. Real child-process kills
at announced force/replace/publish boundaries will check repeated reopen and
that a visible id resolves from the persisted dictionary.

Consumer tests will assert unavailable identity takes the persistent gap branch,
registration failure retains work, and successful flush publishes resolvable ids.
Extend the exact normal/cold allocation gate for dependency rejection. Run serial
Paper/Folia real-client and synthetic rollback regressions. Synthetic players
must explicitly register through the worker before their fixture begins; they
must not gain a bypass of the production identity check. Real-client actions must
wait for actual registration completion, with a bounded asynchronous barrier.

## Self-attack

* Saving after a map update leaves recoverable records with missing identities.
  Force/replace first; make UUID-to-id publication the final visibility step.
* Atomic rename without force is insufficient even for the intended ordering.
  Force the temporary file; do not claim directory-entry power-loss durability.
* Loading dictionaries before acquiring the writer lock lets a rejected second
  runtime modify the first runtime's files. Move lock acquisition earlier.
* A bounded queue that polls before save loses its retry on failure. Peek, persist,
  then acknowledge. Capacity includes reserved and in-flight entries.
* A lock-based offer could wait behind a disk write. Producers use CAS admission;
  the single consumer owns persistence. Admission may reject under overload.
* Unknown actor zero is legitimate for environment changes but not an unidentified
  player. Player listener rejection is separate from core environmental capture.
* A test that supplies a proxy actor without registration would only test loss.
  Add explicit fixture registration and require real ids before actions.
* Masking or silently skipping malformed dictionary lines reinterprets history.
  Refuse duplicates, reserved ids and out-of-range values before changing files.

## What this will not prove

Power-loss durability, arbitrary-instruction crashes, filesystem corruption
recovery, identity erasure, cross-version state migration, full-state/NBT fidelity,
automatic retry after queue-full registration, or complete M4 capture. Queue and
dictionary latency/size costs and listener allocation are **not measured**.
