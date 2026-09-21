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
and cooperative-budget runs use the same dataset generator. Admission limits are
fixture settings. The short-budget run records whichever terminal branch occurred;
the completion fixture must actually merge. Both must observe capture publishing
during maintenance, retain the input row count and pass output verification.

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
