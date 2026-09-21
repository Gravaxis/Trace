# M3 completion audit — 2026-09-21

The five follow-up steps in m3-completion-plan.md were executed in order. This is
an audit of the exercised claims, not a declaration that M3 is complete. M4 has
not started.

## Gate ledger

| Requirement | Evidence | Limit / verdict |
|---|---|---|
| Migration interrupted before/after publication | StorageCrashTest kills format-2 migration after copying and after commit; checks format before recovery, exact hot row and watermark after repeated reopen | Passed at these boundaries; not arbitrary instruction interruption, pre-existing corruption or power loss |
| Producer exhaustion survives process death | Real CaptureService rejects an excess producer; child is killed without close; production CaptureRecovery emits OVERFLOW on reopen | Passed; generic CRASH_WINDOW cannot satisfy the test. Reclamation/spill and marker reset remain absent |
| Bounded/cancellable rewrite | MaintenanceBudgetTest checks admission deferral, subset publication, cancellation after output, native SQLite interruption and deadline branches | Passed cooperatively; OS stalls and monitor acquisition are not bounded |
| Scheduled maintenance | scheduledMaintenanceTest on pinned Paper/Folia captures separate batches and observes consumer-driven merging | Passed; synthetic events, not protocol clients or region-latency measurements |
| Configuration and retention opt-in | MaintenanceConfigTest checks template comments, preserved operator/foreign values including null, backup, future-version/invalid-value refusal; scheduler tests disable and opt-in branches | Passed for current schema; no hot reload |
| Shared backend maintenance contract | EventStoreContract compares all returned event fields including cause/kind, both scan directions and stored suffixes; purge replay exclusion, cutoff/read-lease behavior and blob lifetime | Passed by SQLite. Other backends must run it. Corruption/quarantine atomicity remains SQLite-specific |
| Cancellation and retired-file cleanup | New tests first failed on orphan accumulation and no-work cleanup starvation; then passed after the fix | Cancelled unpublished output is removed; sweeps are bounded and progress past completed unlink records; killed-process orphan cleanup still occurs at startup |
| Reproducible maintenance costs | :benchmarks:maintenanceCosts generates environment and raw sample reports from committed source | Measured synthetic workload only; not a production latency, capacity, native-memory or hard-deadline guarantee |
| Full regression | :benchmarks:m3Evidence runs unit gates plus serial integration, cancellation resume, journal crash, real-crash resume, purge/quarantine and scheduled maintenance | Initial full run passed; corrected-revision final collection is recorded separately when complete |

Build, module graph, formatting and README-table consistency are checked separately.
The report collector's environment describes the collector JVM; server heap/JVM
details are inside the individual harness results. It is not a server measurement.

## What the measurements actually say

The corrected-revision run is
`benchmarks/results/maintenance/2026-09-21-011952-f3b24de1b217/`.
The earlier run remains under
`benchmarks/results/maintenance/2026-09-21-010717-69a5e9e5e055/`.
Both have generated names, clean source commits and raw observations. Neither
reads the private database or changes the SPIKE-2 comparison.

In the corrected run, completion-budget cases merged their admitted inputs and
verified output, but capture filled its ring and dropped records. Short-budget
cases deferred without publishing, with no drops observed in those cases. These
are results for the recorded synthetic burst workload and settings, not a promise
about other workloads. The short runs do not prove eventual compaction progress.
Sampled heap includes the observer and excludes SQLite native/mapped memory;
sampled maxima are not true peaks. See benchmarks/MAINTENANCE.md for reproduction.

## Why M3 remains in progress

* Cooperative abort preserves data but discards unfinished work. A leading shard
  above admission limits, or a pass repeatedly exceeding its deadline, can prevent
  progress indefinitely. Long budgets trade this for consumer backlog and the
  observed capture drops. A resumable rewrite that yields between bounded chunks
  is still needed before claiming both progress and controlled capture impact.
* Scheduled passes do not yet manage WAL checkpointing. wal_autocheckpoint remains
  disabled and close performs the checkpoint. Sustained-run WAL growth is not
  bounded or measured by these short fixtures.
* Full verify and seal are not covered by the rewrite budget. Their real-server
  latency and the effect on region tick latency are **not measured**. Automated
  bounded verification remains outstanding.
* The proposed additive multiset accumulator is not implemented. Production seal
  digests and independent full-field contract oracles are present; they must not
  be described as the missing accumulator. Shared quarantine atomicity also lacks
  a backend-neutral fault-injection contract.

## Limits retained rather than quietly moved

Power loss, failing disks, malicious manifest edits and arbitrary crash timing
remain unproven. Blob storage remains opaque; no atomic captured event/blob
journal protocol, NBT fidelity or container rollback claim follows. Purge does not
erase dictionaries, journals, legacy hot.db, quarantine files, backups or free
space. SPIKE-2 still has different datasets and denominators, with no production
savings ratio. The contended-chunk and overlapping-rollback paths remain untested;
checkpoint and cross-process resume costs are **not measured**. No capture hot-path
bytecode changed during this follow-up; the existing allocation scope remains the
encoder only, not Bukkit dispatch, dictionaries or world reads.
