# SPIKE-2: aggregate-only storage density

Run from the repository root with the pinned wrapper and driver:

```powershell
.\gradlew.bat :benchmarks:test --tests '*DensityReaderTest'
.\gradlew.bat :benchmarks:storageDensity '-Ptrace.density.input=D:\database.db'
```

This boots no server. Do not run it alongside server benchmarks. The private input
must be a quiescent database; the tool holds a read transaction, opens `mode=ro`,
enables `query_only`, and never copies it. Gradle does not hash or cache the input.
Omit the property for a synthetic-only run; private density then says not measured.

The output is `benchmarks/build/density/report.json`, replaced with an explicit
not-measured marker before each run. A completed aggregate report can be copied to
`benchmarks/results/density/<timestamp>-<commit>/density.json` and committed after
review. It is separate from the server benchmark table and does not replace it.
Commit the tool first, then run it. The report records the execution commit and
dirty state; do not describe uncommitted results as measurements.

Queries read only counts, SQLite page pragmas, schema names/types/ownership (never
DDL), and grouped dbstat page counts and allocated bytes. Object and table IDs in
the output are report-local aliases. They permit index-to-table attribution without
serializing arbitrary database identifiers. `rows = -1` means not applicable for an
index/schema object, not a negative count. No source row, user value or rendered
SQL is printed. Error reporting deliberately excludes raw SQLite exceptions.

`pgsize` counts allocated B-tree pages including structural overhead and unused
space within pages, not SQL payload bytes. The report reconciles object pages,
free pages and unassigned pages with logical database length. Unassigned space
(for example pointer-map pages) is reported rather than attributed to a table.
Main-file length and WAL/SHM lengths are separate; physical filesystem allocation
is not measured. A changing live file is not a controlled density input.

Trace uses seeded synthetic numeric IDs and the real append/seal implementation,
with the event count, timestamp span and seal interval recorded. Positions, actors
and states are synthetic, not sampled from the private input. Both timestamp spans
use the same seal interval to expose partitioning effects. Trace accounting includes
all generated manifest/hot/shard databases, including empty schema overhead.
Dictionaries, capture rings, journal and blob payloads are excluded and named in
the report; therefore this is not total plugin disk usage.

The private report covers every table and index and separately identifies the
`co_block` table and its indexes. A logged CoreProtect row and a synthetic Trace
before/after-state record are not established as equivalent units. Different
features, distributions, indexes, histories and auxiliary data prevent a headline
ratio, a production capacity prediction, or a migration savings claim. The private
measurement is rerunnable by its holder; outsiders cannot reproduce that input.

The fixture tests assert nonzero table and index attribution, free-page accounting,
write refusal, no leaked planted values/identifiers, missing-file refusal, and the
actual row count after Trace sealing. They do not establish the private result;
only the completed committed aggregate report does that.
## Recorded M3 measurements

The private baseline is `results/density/2026-09-21-e643f099dcdc/density.json`.
The updated Trace format is `results/density/2026-09-21-e82a6dda926e/density.json`,
measured from clean committed source. Both use the same `DensityReader`.
Format-3 schema overhead includes empty blob and maintenance tables; blob
payloads, dictionaries, capture rings and journals are absent from the fixture.
The second report deliberately marks private input as not measured for that run.
These reports have different workloads and do not establish a savings ratio.
