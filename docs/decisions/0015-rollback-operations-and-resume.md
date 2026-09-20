# ADR-0015: Rollbacks are durable operations, and an interrupted one resumes

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M2

## Context

A rollback changes the world one chunk at a time and can take a long time over a large area. If the
server dies halfway, the result is a region that is neither the state before the grief nor the state
after the repair, and nothing anywhere says so. The next operator to look sees a half-restored
world and no way to tell how far the job got, which parts were done, or how to finish it.

The M2 definition of done therefore requires that an interrupted rollback resumes, and the approved
plan called for "an operation record with a resume cursor" and for reporting `DONE_PARTIAL` rather
than `DONE` when chunks were contended.

Two facts about the existing engine shaped the answer. Rows stop moving before a rollback plans over
them, because it seals first, so a row's identity survives across runs. And the apply step already
verifies each block against the recorded after-state before writing, skipping and counting anything
that no longer matches.

## Decision

**A rollback is a row in the manifest database** (`rollback_op`), written before the first block is
touched: the world, the box, the time window, who asked, the state, the counters, and the last
position durably dealt with. An operation that exists with no matching change in the world is
harmless. A world changed by an operation that was never recorded cannot be continued or explained.

**The window is stored, not recomputed.** This is the part that makes resuming correct rather than
merely possible. A rollback records its own writes, with Trace as the actor, so that it can itself be
undone. A resumed run that worked out a fresh "now" would take those writes into its window and
begin undoing its own work. The stored window ends before them.

**Resuming is a seek.** The stored position becomes a keyset predicate on the clustered key in every
range the scan touches, so the rows already dealt with are never read. Resuming a job that was 90 per
cent done costs the last 10 per cent. No assertion about the returned rows can tell that apart from
reading everything and discarding most of it, so there is a separate assertion about the statement.

**The cursor advances only over chunks that were fully applied.** The moment one chunk cannot be
applied — its region would not accept the task within the timeout — the cursor freezes for the rest
of the run, although later chunks are still attempted. The operation ends `PARTIAL`.

**There is no `INTERRUPTED` state**, because a process killed by `kill -9` cannot write one.
Interrupted is an operation still marked `RUNNING` after a restart. The record carries the run id of
the process that started it, for the operator; liveness is decided by the service, which knows which
of its own operations are running, and by the data-directory lock, which means an operation from an
earlier run cannot still be going.

**Cancellation is per operation**, and a resume refuses an operation this process is already running.

## Consequences

* Resuming twice is harmless, and so is resuming something that was nearly finished: the apply step
  verifies before it writes, so a position already restored is counted as already-there and left
  alone. Correctness does not depend on the cursor being exact, only on it never moving past work
  that was not done.
* Summaries report this run's counters and the operation's totals separately. A resumed run inherits
  the interrupted run's numbers, so a test asserting on the totals would pass having resumed
  nothing.
* One more table to keep, one row per rollback, and a checkpoint write per chunk. The write is a
  single `UPDATE` on a manifest row, on the same connection everything else uses, which is now
  serialised — a rollback runs on the async scheduler while the consumer thread is appending, and
  they were sharing a JDBC connection with no lock.
* `/trace status` and startup both report unfinished operations, with the command to continue them.
  A half-applied world that nobody is told about is the failure this whole record exists to prevent.
* The storage format version moves to 2, and opening an older directory rewrites it. The version
  only used to be written when absent, so a directory from an older build silently gained the new
  tables while still claiming the old version, and an older Trace would have reopened it and not
  seen the unfinished rollback.

## What is proven, and what is not

Proven, on Paper and on Folia, by the `rollback-resume` scenario: a rollback cancelled mid-flight
stops at a chunk boundary with 40 of 240 positions restored, is listed as unfinished, and a resume
restores exactly the remaining 200 across the other five chunks and ends `DONE`. The world is
checked block by block afterwards, not just the counters.

Not proven, and not claimed:

* **The cost of a checkpoint per chunk is not measured.** No figure for it appears anywhere.
* **Resume after a real crash** — rather than a cancellation — is not covered by a test. The
  cancellation reaches the same state deliberately, which is why it is used, but a crash landing
  inside a rollback is a different timing and has not been exercised.
* **Two rollbacks over overlapping boxes** are permitted and untested.
* **An operation whose world is gone** is refused, and that refusal is not covered by a test.

## Alternatives considered

* **Re-scan from the start and skip applied rows in Java.** Correct, and it makes resuming a large
  job cost as much as the job. The keyset predicate was already there for paging.
* **Resume automatically at startup.** Rejected for now: a world-mutating operation restarting by
  itself after a crash, before an operator has looked at anything, is a large amount of trust to
  place in a cursor written by a process that then died. Startup reports it and gives the command.
* **A heartbeat column to detect a live operation.** Unnecessary: one server per data directory is
  already enforced by a lock file, so the only process that can be running an operation is this one,
  and it knows.
