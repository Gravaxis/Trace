# M3 maintenance measurement and evidence

Commit the tool and implementation first. Run alone on the machine, with no test
server or density walk in progress:

```
./gradlew :benchmarks:maintenanceCosts
```

The task generates `results/maintenance/<UTC timestamp>-<commit>[-dirty]/` itself,
including environment.json and raw measurements.json. Commit the generated files;
do not rename directories or type results. An interrupted/failed run retains a
not-measured marker. The task does not open the private database.

It seeds sealed synthetic block rows with the real append/seal path, then uses
real CaptureService, JournalWriter and StoreConsumer. The producer first publishes
a batch, waits for scheduled compaction to start, then keeps publishing bursts.
This deliberately tests overlap, not a steady-state arrival process. Completion
and cooperative-budget runs use the same dataset generator. Row budgets are
fixture settings. Both runs must now complete a pairwise incremental merge;
the earlier abort/defer reports retain their original meaning. Both must observe
capture publication during maintenance and preserve seeded plus stored capture
rows. The scheduler may proceed to later phases before the observer stops it;
all resulting live shards must verify. No-work or checkpoint completion cannot
stand in for compaction: the observer uses the separate compaction counter.

Isolated phase fixtures measure incremental seal, content verify and checkpoint
separately, recording total and maximum observed call duration and branch counts.
Seal/verify must exercise both progress and completion; output count and verification
are checked. These fixtures do not include concurrent capture. The server evidence
also records ticks over the synthetic scheduled scenario's whole capture/seal/wait
window, including mixed regions on Folia. It has no control arm and cannot assign
causal tick overhead to maintenance.

Each raw sample is elapsed nanoseconds, pending ring records, used Java heap,
maintenance-active flag, and published capture counter. Sampled maxima are not
true peaks. Heap includes monitoring overhead and excludes SQLite native memory
and mapped ring pages. The dedicated maintenance duration excludes fixture setup;
the outer elapsed duration includes scheduling/draining. No production throughput,
latency SLA, native-memory bound or default-budget progress guarantee is inferred.
Drops are retained as measured outcomes, not omitted to make backlog look smaller.

Generate correctness evidence separately, after all source changes:

```
./gradlew build verifyModuleGraph
./gradlew :benchmarks:m3Evidence
```

That task depends on the unit and serial pinned-server gates, validates their
success, and copies only named result artifacts into a generated commit-labelled
directory under results/m3-validation. It never copies server logs, worlds,
databases, player caches or source data. These results leave the README's existing
server benchmark table unchanged.

For a scoped maintenance correction after a full regression, use
`:benchmarks:m3MaintenanceEvidence`. Its generated complete.json explicitly names
the narrower scope: unit gates and serial purge, quarantine and scheduling on both
platforms. It does not copy earlier integration or server-crash results under the
new commit; cite the separate full-regression report for those gates.
