# M4 bounded payload handoff — 2026-09-22

## Plan before implementation

Verified `BoundedPayloadQueue.peek/acknowledge`, `JournalWriter.appendEvents`,
`JournalReader.FrameHandler.frame`, `StoreConsumer.flushBuffer/serveRequests`,
`TraceRuntime.recover`, `EventStore.append/putBlob/attachBlob`, and
`SqliteEventStore.append` from local source. The queue currently has no consumer.
The store currently commits event and attachment separately. A failed earlier
frame can be skipped if a later append advances the single applied watermark.

* Core journal: add a versioned exact-byte capture envelope and a dedicated frame
  type. Keep legacy callbacks; their default payload handler must refuse, never
  discard trailing bytes. Validate supported semantics after CRC/LSN validation,
  including during writer reopen before truncation. Fix stale salt documentation.
* Storage API: add `appendCaptured` for one event and opaque versioned payload.
  Add a shared `PayloadHandoff` that journals, forces, atomically applies, then
  acknowledges. Rejections become persisted gaps before a successful flush.
* SQLite: publish event, blob, reference and applied watermark in one transaction.
  Exact retry at a newer LSN is idempotent across hot and sealed rows; conflicting
  identities refuse. Purge/retention exclusions precede payload insertion. Bump
  the manifest format so the preceding runtime refuses this data directory before
  opening the journal. Dictionary ids must already be persisted in this slice;
  dictionary additions and payload interpretation remain the next capture design.
* Runtime/consumer: recover pending payload queues after journal replay, close
  startup handles and reopen for consumer ownership. Use a fixed startup pool;
  future producers explicitly select an exclusive slot. Drain payloads before
  ordinary batches, propagate failures and make flush persist rejection gaps.
  Do not enable new Bukkit listeners or claim block-entity fidelity.
* Proof: extend the inherited EventStoreContract, journal tests and real child-JVM
  failure tests. Inject transaction failure after event insertion, verify no row,
  reference or watermark survives; kill at queue publication, journal force,
  transaction and commit boundaries. Reopen twice and compare exact bytes and
  identity. Test full/oversized rejection, gap failure, malformed semantics,
  legacy replay, odd payload lengths, salt changes, exclusion and retry.

## Self-attack

* Acknowledging after copying loses the only retry source: acknowledge only after
  forced journal plus store commit. Store failure prevents later watermark moves.
* Commit-before-ack duplicates events on recovery: compare full stored identity and
  content, including payload hash; never blindly insert a retry at a new LSN.
* Queue ownership survives Java thread changes: startup recovery uses separate
  mappings, closed before opening consumer-owned mappings.
* A checksum-valid unknown envelope is not a torn tail: throw before writer
  truncation. Salt changes remain boundaries, not corruption.
* A rollback flush that neglects overflow gaps lies about completeness: gap writes
  are part of its success condition. Failed gap writes retain mapped bounds.
* A format bump without a downgrade refusal is cosmetic: preceding format reader
  already rejects newer manifest versions before journal open; test future format
  refusal and migration preservation.
* Permanent malformed data cannot be silently dropped: fail startup/handoff and
  retain the queue. No automatic destructive repair is introduced.

## Proof limits

Opaque bytes and already-persisted dictionary ids only. No new listeners, NBT
fidelity, dictionary publication, payload allocation claim, power-loss guarantee,
arbitrary-instruction crash coverage, producer-slot reclamation or spill. Queue
throughput, allocation and latency are **not measured**. Fixed pool capacities
are defensive configuration bounds, not measured optima. M4 remains incomplete.

## Implementation audit

Implemented as [ADR-0023](../decisions/0023-bounded-payload-handoff.md). The shared
contract injects reference-insert failure after row insertion and asserts no row,
blob, reference or watermark survives. The consumer test deliberately holds that
failure while an ordinary ring contains a later event, and requires the ring and
watermark to stay untouched and flush to fail. After removing the fault, both
records must be sealed and the payload must match exactly.

Review found a separate journal retry trap: after a partial write, the channel's
position can differ from the writer's logical position. It now refuses all later
writes/force until reopen, with an injected after-header regression. Valid unknown
envelopes fail before truncation. Recovery failures close the newly owned store and
journal; shutdown retains live-worker mappings and omits a clean marker if flush
failed. Queue publication bounds include other pending payload queues.

`./gradlew :benchmarks:payloadIntegrationEvidence` reruns the named unit suites,
real process-kill branches and serial pinned transport/client/rollback scenarios.
Its generated source-labelled results are the proof record. The runtime payload
scenario does not claim a natural Bukkit payload producer: it submits a synthetic
record with already-persisted world/material/built-in actor ids, and checks exact
row/bytes, the oversized branch, covering gap, empty queue and refusal through the
actual RollbackService entry point. No block mutation is needed by this fixture.
