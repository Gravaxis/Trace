# ADR-0024: Missing confirmation is loss; repeated positions are net observations

* Status: implemented; scoped evidence recorded
* Date: 2026-09-22
* Milestone: M4 prerequisite

This supersedes ADR-0014's unconfirmed-publication fallback and its assumption
that each event at the same position can independently describe the final state.
A before=after row cannot distinguish an unchanged world from an unavailable
observation. Verifying that row during rollback does not recover the missing
post-state. It also conceals a loss from the gap refusal mechanism.

Staging exhaustion, unavailable post-state and explicit unconfirmed flush now
publish no row. They increment distinct loss counters and widen the mapped
capture-loss bounds. `unconfirmed` remains observable but counts observations
discarded without confirmation. Invalid packed field widths, including returned
state ids, are counted and gapped before encoding. `dropped` includes rejected
coordinates and fields as well as transport and confirmation losses.

Within a producer's tick, a fixed primitive index groups by world and position.
The first before-state and capture time survive. Confirmation reads once and
compares against that first state. An unchanged final state rejects all grouped
observations; a changed final state with matching actor/cause/kind yields one net
row. Mixed attribution on a changed position is counted loss with a covering gap.
It does not receive a fabricated actor or a misleading per-event after-state.

The index does not grow and its entries are reset after confirmation or flush.
Duplicate positions can still join a full staging buffer. Hash collisions probe
and compare the complete world/coordinate key. `coalesced` counts duplicate
observations, not emitted rows. No cross-producer or cross-tick coalescing occurs.

This establishes neither sub-tick causality nor full-state/NBT fidelity. Losing
an ambiguous net observation is deliberately conservative. Gaps may cover more
history than the actual loss, as with existing capture-loss handling. Spill,
producer reclamation, power loss and listener allocation remain outside this
change. The completion plan names the required branch and server regressions.

The [clean-source evidence](../../benchmarks/results/confirmation/2026-09-22-142522-a49e3ad075b0/complete.json)
contains the named core and consumer tests, normal/cold exact allocation reports,
JMH allocation gate and serial Paper/Folia client/rollback/resume regressions.
This does not extend the server scenarios' natural-event coverage to the new
unit-tested edge cases. The initial incomplete collector result is retained;
JUnit display names required correction before a complete report could be made.
