# ADR-0023: A payload queue entry is released only after atomic publication

* Status: implemented; evidence recorded by the payload integration task
* Date: 2026-09-22
* Milestone: M4 prerequisite

`CaptureEnvelope` carries one packed event and exact opaque payload bytes, including
the payload codec version. `JournalWriter.appendCaptured` writes it in a dedicated
frame; CRC32C and absolute LSN retain ADR-0013's integrity rules. Supported semantic
decoding follows integrity validation. Unknown envelope versions refuse replay
and writer reopen; they are not truncated as a torn tail. Legacy frames remain
readable. Legacy callback implementations refuse payload frames by default.

`EventStore.appendCaptured` commits the event, content-addressed blob, reference
and applied LSN in one manifest transaction. Retention and purge exclusions are
checked before inserting any payload. Retrying an unacknowledged event under a
new LSN compares the stored fields and payload identity, including after seal or
compaction. Conflicts refuse. As with append, frames at or below the applied
watermark are already-applied no-ops. Auxiliary/sidecar bits not represented by
the storage columns refuse rather than silently disappearing.

Storage format 4 fences the preceding format-3 runtime out before its journal
writer opens. This is an intentional forward-only upgrade. It does not establish
safe downgrade using arbitrary older binaries or copying journals into unrelated
data directories. The previous blob APIs retain their documented separate commits.

`PayloadHandoff` keeps the mapped entry pending until journal force and atomic
store publication succeed. The live consumer processes these handoffs before
ordinary rings and stops that pass on failure, so a later frame cannot advance
the watermark past a failed payload. A partial journal write poisons its writer
until reopen. A successful flush includes persistent loss-to-GapRecord conversion.
If the first rejection leaves an uninitialized bound, recovery refuses the entire
time range rather than inventing a narrow interval. Bounds remain in the queue file;
restart may conservatively record another overlapping gap.

`PayloadQueues` opens a fixed pool at startup; no mapping or growth happens on a
producer thread. Slots need explicit exclusive ownership. Startup recovery closes
its handles before opening the consumer's handles. Shutdown does not write a
clean-close marker after failed flush, or close mappings under a live consumer.
Rollback preflight streams the entire remaining selection before world mutation
and refuses opaque payloads and non-block kinds.

The [plan and self-review](../design/m4-payload-integration.md) names the gates and
limits. Tests cover transaction/gap/journal failure, queue retention, ordinary
watermark ordering, exact retry across maintenance, exclusions, and child-process
kills at named queue/journal/transaction/commit/acknowledgement boundaries. Pinned
runtime payload scenarios use synthetic records, alongside the existing natural
client capture and rollback regressions.

This is not complete M4 capture. Dictionary ids must already be durable; ordered
dictionary additions, NBT interpretation, payload listeners and restoration remain
unimplemented. No power-loss or arbitrary-instruction crash guarantee, spill or
slot reclamation follows. Payload allocation, throughput and latency are **not
measured**. Existing allocation gates retain their earlier encoder scope.
