# ADR-0025: Dictionary ids become visible only after persistence

* Status: implementation; committed-source evidence pending
* Date: 2026-09-22
* Milestone: M4 prerequisite

The existing actor/world/material dictionary files remain authoritative. A writer
forces a proposed file and atomically replaces the previous file before publishing
new ids to capture. Failure before replacement preserves the old dictionary;
failure after replacement can leave an unused durable id, but no visible volatile
id. Unknown temporary files are not promoted during load. Malformed, duplicate,
reserved or out-of-range mappings refuse rather than being skipped or renumbered.

This refines the original M4 envelope proposal: dictionary dependencies precede
all dependent event publication rather than travelling in the event envelope.
The storage writer lock is acquired before startup dictionary writes. Dictionary
startup failure releases it. This is not a migration to SQLite dictionaries.

Player joins and later world registration enqueue detached UUID/name values into
a bounded CAS-admitted queue. The storage worker peeks, persists, then acknowledges.
Failure retains the head and prevents a successful flush. Producers never wait
for a file write. Requests can be rejected at the admission bound; the operator
sees backlog/rejection counters. There is no unbounded secondary queue or automatic
retry list. A later registration/rejoin can retry rejected work.

An unpublished actor or world id is unavailable. A player capture requiring it
increments the dependency-loss counter and widens the persistent gap bounds; it
does not masquerade as environmental actor zero. Registration requests themselves
are not world mutations. Their volatile queue can be lost on process death without
leaving a dependent recoverable row. A rename failure retains the previous name
and stable player UUID/id; it does not make an existing identity unavailable.

The [plan/self-review](../design/m4-dictionary-publication.md) names the proof and
limits. Process-kill tests cover announced force, replacement and publication
boundaries. They do not prove power-loss directory durability, arbitrary instruction
crashes, corruption recovery, erased identities, cross-version state migration,
full-state/NBT capture or complete M4. Costs and listener allocation are **not measured**.
