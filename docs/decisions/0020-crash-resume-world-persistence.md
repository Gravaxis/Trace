# ADR-0020: Cross-process rollback resume revisits uncertain world writes

* Status: implemented; real-crash scenario passed on pinned Paper and Folia
* Date: 2026-09-21
* Milestone: M3 debt absorption

ADR-0015's cursor proves Trace reached a checkpoint, not that Minecraft saved the
modified chunk. Those writes do not share a transaction. After process death, a
stored cursor can therefore be ahead of the persisted world. Resuming strictly
after it can silently leave part of the grief unrepaired.

On explicit resume of an operation from a different process, Trace keeps the
original time window and selection, clears the cursor, changes the run identity,
and restarts counters for the new verification epoch. It rechecks each block
before applying. Already restored blocks remain untouched; blocks whose writes
were lost can be restored again. Same-process cancellation still uses its keyset
cursor. Cross-process resume no longer claims work proportional to the unprocessed
suffix. Its cost is **not measured**. Prior-run progress is not a durable-world
count and is not added to this epoch's totals.

`crashRollbackTest` has three serial boots per platform: a clean setup shutdown
persists the broken world and its history; a new process starts a rollback, reaches
an asserted nonempty checkpoint with work remaining, and is forcibly killed;
the verifier reopens, asserts RUNNING partial progress, actually compacts history,
resumes, requires new work and DONE, and checks every target block on its owning
region thread. It is not a cancellation masquerading as a crash.

This proves the exercised process-kill/checkpoint branch on the pinned platforms.
It does not prove all crash timings, power loss, overlap between concurrent
rollbacks, or the contended-chunk apply branch. It does not automatically resume
anything at startup. Gaps still refuse resume; no exception was added to make the
test pass. The earlier ADR-0013 recovery instruction to resume automatically is
superseded by this explicit operator-resume policy and ADR-0015.
