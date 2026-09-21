# ADR-0018: Density is page accounting with explicit denominators

* Status: baseline tool and aggregate result committed; M3 Trace rerun pending
* Date: 2026-09-21
* Milestone: M3

SPIKE-2 uses the pinned sqlite-jdbc and its dbstat implementation for both private
CoreProtect input and synthetic Trace stores created through append/seal. The
repeatable entry point is `:benchmarks:storageDensity`; its method and exclusions
are in `benchmarks/STORAGE-DENSITY.md`. No server boots and no new dependency is
introduced. Fixtures test the measurement and disclosure boundaries before the
real input is queried. Only aggregate reports may be committed.

Allocated table/index pages are not payload bytes. Freelist and unassigned pages,
main file length and WAL/SHM are separate. Trace fixtures contain synthetic IDs and
exclude dictionaries, capture rings, journals and blobs; the private database has
a different workload and semantic content. Results do not license a headline
compression ratio, migration savings, production capacity, or throughput claim.

Density reports live under `benchmarks/results/density/` so a density-only run does
not replace the existing server benchmark table. A run begins by replacing its
output with a not-measured marker. Raw source exceptions are not emitted. Private
object names are replaced with report-local aliases while retaining index/table
relationships and explicit block-history attribution.

The first observation in provenance remains historical and unpublishable on its
own. Numbers from a completed report are not established until that report is
committed. The private input is rerunnable by its holder, not reproducible by an
outsider who lacks it.

The first committed task result is
`benchmarks/results/density/2026-09-21-e643f099dcdc/density.json`, measured by tool
commit e643f099dcdc. Its Trace side is the pre-M3 store loaded when the task began.
It reports a dirty working tree because independent M3 edits proceeded during the
private page walk. Unit builds shared the machine; elapsed time is execution
metadata, not an uncontended performance benchmark. M3 Trace must be measured
again after its implementation is committed; the private page walk need not be
repeated for that synthetic-only run.
