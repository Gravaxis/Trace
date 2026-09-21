# Working on Trace

Read this before you change anything. It is the working agreement for this repository: the rules,
what is already true, and what will fail if you ignore them. It is written for an automated agent
but a human contributor gains from it too.

Trace is a block, container and entity logging plugin with rollback, for Paper and Folia servers.
Nothing is released. Milestones M0 to M3 are complete; M4 has not started.

Four documents carry the rest: [ROADMAP.md](ROADMAP.md) for what is built and, under each item,
what is **not** established; [docs/decisions/](docs/decisions) for the ADRs that bind future
work; [CONTRIBUTING.md](CONTRIBUTING.md) for the contributor-facing version of the rules below; and
[docs/design/m3-storage-engine.md](docs/design/m3-storage-engine.md), a reviewed proposal for the
next milestone whose most useful half is the list of traps a reviewer found in it.

---

## 1. The four rules that are not negotiable

**Clean room.** Never open, fetch, quote, paste or transcribe source code, build files, database DDL
or resource files from **CoreProtect, WorldEdit, FastAsyncWorldEdit, LogBlock or Prism**. That
includes GitHub file views, `raw.githubusercontent.com`, code-search results, and forum posts that
paste their source. Their published documentation, javadoc, READMEs, release notes and listing pages
are fine, and so is observing their software or the data it produces as a black box. Paper,
CraftBukkit, Folia, Moonrise, Adventure and OpenJDK source may be read freely. If a task appears to
require forbidden source, **stop and say so** rather than working around it.

**No invented numbers.** Every performance, size or density figure — in code, comments, commit
messages, pull requests, docs or the README — must come from a run of the in-repo harness whose raw
result is committed under `benchmarks/results/`. A measurement that is not committed does not exist.
Console output of a run nobody kept is not a source. "Should be faster" is not an argument. If you
do not have a measurement, write "not measured". Never write "certified", "enterprise-grade",
"compliant" or "99.9% uptime".

**A test that passes without testing its claim is the worst defect here.** Worse than a crash,
because it makes a false claim durable. Every gate asserts which branch it took: the allocation tests
assert the counters alongside the bytes, and the crash rig fails a run in which *nothing was lost*,
because a durability check with nothing to check is not a pass. When you add a gate, ask what would
have to break for it to fail, and whether that is the thing you are claiming.

**Report honestly.** An unmet definition-of-done item is reported as unmet. Do not mark a milestone
done, or carry an item quietly into the next one, without the gate that proves it. Do not weaken,
delete or contradict any `*Not established:*` note in ROADMAP.md — they are the project's honest
statements about the limits of its own gates. Work milestones in order; do not skip ahead to
features.

---

## 2. Before you guess an API, check it

Model training data predates this Minecraft version. Verify rather than recall:

```bash
JAR=$(ls ~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.2*/*/paper-api-*.jar | grep -v sources | head -1)
javap -cp "$JAR" org.bukkit.Bukkit | grep -i whateverYouNeed
```

Any externally-derived load-bearing fact — a version pin, an API contract, a licence claim, an
observed platform behaviour — gets a dated line in
[docs/decisions/provenance.md](docs/decisions/provenance.md) saying what you read, where, and when.

---

## 3. What the build will reject

These fail `./gradlew build`. They are not style preferences.

| Rule | Enforced by |
|---|---|
| Java 25 only. Do not touch `java = "25"` in the catalog or add `sourceCompatibility` anywhere | toolchain + `options.release` in `trace.java-conventions` |
| Every package has a `package-info.java` with `@NullMarked` | NullAway at `CheckSeverity.ERROR`, `OnlyNullMarked=true` — an unmarked package is silently **unchecked** |
| SPDX header on every file: Apache-2.0 in `trace-api`, GPL-3.0-or-later elsewhere | Spotless `licenseHeader`; never hand-write it, run `spotlessApply` |
| Never hand-format Java | Spotless + palantir-java-format 2.98.0, 4-space Java, ~120 cols |
| Module graph: api→nothing, core→api, storage-api→api+core, storage-sqlite→+storage-api, paper→all, harness→api only | `verifyModuleGraph`, walks the whole resolved classpath |
| No `org.bukkit.*` or `io.papermc.*` in `trace-core` or `trace-storage-*` | structural: paper-api is `compileOnly`; pass a functional interface instead (see `CaptureService.StateReader`) |
| No `repositories { }` in a module build file | `FAIL_ON_PROJECT_REPOS` in settings.gradle.kts |
| Every paper-api call must also exist in folia-api | `compileAgainstFoliaApi`, wired into `check` |
| Never hand-edit between `<!-- bench:start -->` and `<!-- bench:end -->` in README.md | `verifyReadmeTable` |
| Capture path allocates zero bytes | `ExactAllocationTest` (gate of record) + `checkAllocationGate` |

Conventions with **no** mechanical enforcement, so review is the only guard: the clean-room rule,
the honesty rule, dynamic-version bans, `compileOnly` scoping for server-provided libraries, and the
DCO sign-off.

---

## 4. Architecture invariants

**Folia.** Never `Bukkit.getScheduler()` and never `BukkitRunnable`. Region, async or global-region
schedulers only. A block may only be touched from the thread owning its region. Nothing in
`trace-paper` may block a tick thread; blocking work (flush, seal, scan, rollback) says so in its
javadoc and runs on the async scheduler.

**The capture hot path allocates nothing.** That is `CaptureService.captureBlockChange` /
`confirmStaged` / `publish` and everything they reach. No object creation, boxing, string
formatting, logging, thrown exceptions or capturing lambdas. Stage into preallocated primitive
arrays; return a sentinel rather than throwing; bump an `AtomicLong` for anything dropped. Do not
measure this path through a wrapper — the gated bytecode must be the bytecode the listener calls,
which is why `CaptureService` lives in `trace-core`.

**Log changes, not attempts** (ADR-0014). A record is written only if the world actually differs at
the end of the tick it was captured in. This is why a client mod spamming break events at an
unbreakable block produces no history.

**Every loss is covered by a gap.** Anything dropped, out of range, or possibly lost must produce a
`GapRecord`, and a gap's bounds may only widen, never narrow. A rollback whose window touches a gap
refuses rather than rebuilding a world that never existed. Never truncate out-of-range input; reject
it loudly and gap it.

**Journal frame validity has exactly two rules**: the CRC32C covers the whole header except the CRC
field plus the payload, and `frame.lsn` equals the byte position it was read from. Do not add a
third. A salt mismatch is a restart boundary, not end-of-log — assuming otherwise once destroyed
every previous run's frames.

**Reads stream.** A cursor fills a caller-owned reusable columnar `MutationBatch` and pages by
keyset. Never `OFFSET`, never `COUNT(*)` over a data query's predicate, never materialise a scan into
a list. `ScanSqlTest` asserts the generated SQL.

**A new storage backend** subclasses `in.gravaxis.trace.storage.testing.EventStoreContract` and
implements `open(Path)`. Do not write a parallel suite, and never weaken a contract test to make a
backend pass.

**Rollback operations** (ADR-0015): write the `rollback_op` row before touching a block, store the
time window rather than recomputing it on resume, advance the cursor only over fully applied chunks,
freeze it at the first contended chunk and end `PARTIAL`. No `INTERRUPTED` state, no automatic
resume at startup.

---

## 5. How the code reads

Class javadoc explains **why the class is shaped as it is and what failure it avoids**, not what its
methods do. Inline comments appear only where a reader would ask "why this branch?", and answer by
naming what the alternative would cost. Never restate the code. Cite the governing ADR by number.

Verbatim, from the capture path:

```java
// The slot's clock would have to drift further than ordering allows. Dropping is
// honest; misdating the record would corrupt the order history is read in.
```

Nullability is `org.jspecify.annotations.@Nullable` only. No `java.util.Optional` anywhere, and no
defensive `requireNonNull` in main source — an unannotated parameter is non-null by contract.
Storage failures are `StoreException` with an explicit `Reason`; `IllegalArgumentException` and
`IllegalStateException` are for programming errors only.

Tests are JUnit 6 + AssertJ. **Do not add jqwik, junit-quickcheck, QuickTheories or Mockito.**
Property tests are ordinary `@Test` methods calling `Properties.forAll(...)` from `trace-core`'s test
fixtures, seeded at 20260920 so failures reproduce. Shared test code lives in `testFixtures`, not in
test-scoped helpers.

Packages are `in.gravaxis.trace.*` — note the `in.` TLD. Beware that
`in.gravaxis.trace.capture` (the Paper listener) and `in.gravaxis.trace.core.capture` (the engine)
are different packages with similar names.

---

## 6. Commands

Fast loop, no server:

```bash
./gradlew build verifyModuleGraph          # the gate before any commit
./gradlew spotlessApply                    # before every commit
./gradlew :trace-core:test :trace-storage-sqlite:test
./gradlew :benchmarks:testWithoutEscapeAnalysis   # after any capture-path edit
```

**Server tasks boot real pinned Paper and Folia builds, and only one can run at a time on this
machine** (a Gradle shared service serialises them within a build, but cannot stop a second
concurrent `gradlew`). They take minutes: `smokeTest`, `integrationTest`, `crashJournalTest`,
`benchmark`, `runServer`, `runFolia`.

`./gradlew benchmark` **rewrites README.md** and writes `benchmarks/results/<date>-<commit>/`. Run it
only on a clean tree you intend to commit from. Never create, rename or hand-edit a results
directory: its name and the `commit` field inside `environment.json` are the route from a published
number back to the code that produced it.

Property-test scale: `-Dtrace.property.cases=100000 -Dtrace.property.seed=<n>` (system properties).

---

## 7. Known defects and unproven claims

**2026-09-21 follow-up:** the list below records the M2 baseline. Producer-slot
exhaustion is now contained by counted rejection and persistent loss bounds, with
a real process-kill test (ADR-0019); reclamation/spill remains absent. Real-crash
rollback resume now passes on both pinned platforms (ADR-0020). trace-paper now
has configuration unit tests. The remaining historical limits below still apply.
For the final M3 gates and measurement limits, read
[the final audit](docs/design/m3-final-audit.md), not the older proposal
as if every proposed item had shipped.

Do not describe any of these as working. The full list lives in ROADMAP.md under each milestone.

- **Beyond 32 capture threads, records can be silently overwritten** with no drop counted and so no
  gap. The 33rd thread opens a second mapping of the last slot's ring with a second clock, into a
  ring that is single-producer by construction. The specified fix (one ring per slot, claimed by
  compare-and-set, with reclamation) does not exist. See ADR-0012.
- **On Windows an unclean start always writes a `CRASH_WINDOW` gap**, because no stable boot identity
  is available without native calls. A Windows host gets a rollback refusal where Linux would not.
- **The durability gate exercises one loss mode**: records staged in memory when the kill lands. A
  failed journal or store write, and records dropped for a full ring or an unorderable clock, both
  produce a gap and neither is tested.
- **Gate P1 covers the encoder, not the listener** — Bukkit event dispatch, dictionary lookups and
  the tick-end world read are not measured and must not be called zero-allocation.
- **Untested rollback paths**: resume after a real crash (as opposed to a cancellation), the
  contended-chunk path, two rollbacks over overlapping boxes, and a refusal when the world is gone.
- **`trace-paper` has no unit tests at all**; it is covered only by server scenarios.
- **ArchUnit and MockBukkit are in the catalog but unused**, so the structural rules ADR-0004
  describes are not enforced. The forbidden-apis plugin is likewise declared but never applied —
  there are currently **zero** forbidden-API rules in force despite the Folia scheduler ban above.

---

## 8. Decisions only the owner makes

Do not settle these yourself: ratification of ADR-0009's allocation-gate deviation (it is the only
ADR not fully accepted); reserving the Modrinth, Hangar and Maven Central namespaces and any
trademark filing; the Oasis README URL; and whether the AGPL question raised in
`docs/competitive/oasis.md` reopens ADR-0006 — **the default is no, ADR-0006 stands.**

Never publish, quote or compare against any performance figure from `docs/competitive/oasis.md`.
They are a competitor's self-reported numbers, unverifiable by design, and they may not become a
baseline for anything.

---

## 9. This machine

Windows, Git Bash available, JDK 25 at `C:\Program Files\Java\jdk-25.0.4`. Use `./gradlew`, never a
system Gradle.

`D:\database.db` is a real 12.35 GB CoreProtect database the owner supplied for SPIKE-2. It holds
real player data, including IP addresses in `co_session`. Read aggregates only — page and byte totals
from `dbstat`, row counts — never rows, and commit nothing derived from it until the measurement task
exists. It has been observed once already; the method and findings are in
[provenance.md](docs/decisions/provenance.md) and the figures are explicitly not publishable. Mapped ring files cannot be deleted on Windows while the mapping is live, so any test
using `@TempDir` with a `CaptureService` or `MappedEventRing` must close it in `@AfterEach`.

Commits are authored `Madhav Kothandaraman <info@gravaxis.in>`, Conventional Commits, one concern
each, signed off with `git commit -s`. A commit touching the capture hot path states its
allocation-gate result. **Add no AI or tool attribution trailer of any kind.** Do not push; the owner
pushes.
