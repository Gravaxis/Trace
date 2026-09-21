# Provenance

Every externally-derived fact Trace relies on, with what was read and when. This file exists for two
reasons: so a reviewer can re-check any claim, and so the project can show exactly where its
knowledge of other projects came from if the question is ever put (see the clean-room rule in
`CONTRIBUTING.md` and §2.2 of the build brief).

## Clean-room statement

No source code, build file, database DDL or resource file belonging to **CoreProtect, WorldEdit,
FastAsyncWorldEdit, LogBlock or Prism** has been opened, fetched, quoted or summarised during this
project. For those projects only published documentation, javadoc, README and marketing text,
release notes, listing pages and issue titles were consulted, and they are marked below.

Paper, CraftBukkit, Folia, Moonrise, Adventure, OpenJDK and permissively licensed tooling were read
as source where useful; they are listed too. Trace's implementation is its own work.

## 2026-09-19 / 2026-09-20 — platform and tooling verification

Method: automated read-only research (web search and fetch) against primary sources, followed by an
independent cross-check that re-fetched the load-bearing facts.

### Minecraft and Paper

| Source | What was read |
|---|---|
| `piston-meta.mojang.com/mc/game/version_manifest_v2.json`, `26.3.json`, `26.2.json` | Current release 26.3 (2026-09-15), Java requirement 25 |
| `repo.papermc.io/.../io/papermc/paper/paper-api/maven-metadata.xml` and the 26.2.build.124/126 POM and module | Version scheme, latest stable build, transitive dependencies, `org.gradle.jvm.version=25`, Adventure 5.2.0 |
| `fill.papermc.io/v3/projects/paper`, `/versions/26.2`, `/versions/26.2/builds`, `/versions/26.3` | Channels, latest builds, sha256 checksums, minimum Java, recommended flags |
| `fill.papermc.io/v3/projects/folia`, `/versions/26.2/builds/latest`; `repo.papermc.io/.../dev/folia/folia-api/maven-metadata.xml`; Folia `ver/26.2.x` README and `gradle.properties` | Folia 26.2 build 7 BETA, no 26.3, `folia-supported` requirement |
| `docs.papermc.io` — project setup, paper-plugins, plugin-yml, userdev, folia-support, databases, command API, custom arguments, i18n | api-version parsing, dependency block semantics, Mojang-mapped runtime, bundled JDBC driver, Brigadier registration |
| PaperMC/Paper sources (`paper-server/build.gradle.kts`, `PaperClasspathBuilder`, `PaperPluginClassLoader`, `MavenLibraryResolver`, `CraftBlockState`, `CraftBlock`, `CraftBlockEntityState`, `Block` patch, `PaperPluginMeta`, `ApiVersion`, `PaperCommands`, `SimpleCommandMap`) | Bundled sqlite-jdbc 3.49.1.0, parent-first classloading, Maven Central mirror requirement, block-update flag values (`revertPlace` = 818, `UPDATE_SKIP_POI` = 4096 "temporary"), api-version rules, command override semantics |
| `jd.papermc.io/paper/26.2/` | Dialog API (not experimental; `DialogInput` sealed to four types; `DialogAfterAction.WAIT_FOR_RESPONSE`), `Commands.restricted`, `ArgumentTypes` list and `time()` semantics (ticks; `d` = in-game day), schedulers, `getTickTimes`, `sendMultiBlockChange` |
| PaperMC/Moonrise (`mc/26.2`) | `starlight$serverRelightChunks` signature and behaviour |
| `openjdk.org/jeps/472`, `/491`, `/498`, `/508`, `/519`; JDK 25 `java` man page; OpenJDK `Module.java`, G1 `g1HeapRegion.cpp` | Native-access default is `warn`; one warning per unnamed module; compact headers off by default in 25; Vector API still incubating; G1 region sizing and the humongous threshold |

### Build and runtime libraries

`services.gradle.org/versions/current` (Gradle 9.7.1 + sha256), the Gradle compatibility matrix
(Java 25 needs 9.1.0+), and Maven Central / Gradle Plugin Portal `maven-metadata.xml` for: Shadow
9.6.1, run-paper 3.1.0 (requires Gradle 9.7+; `runFolia` inherits the version), resource-factory
1.3.1, vanniktech 0.37.0, Spotless 8.10.2, palantir-java-format 2.98.0, Error Prone 2.50.0 and its
plugin 5.1.1, NullAway 0.14.1, JSpecify 1.0.1, JUnit BOM 6.1.3, AssertJ 3.27.7, ArchUnit 1.5.0,
MockBukkit `mockbukkit-v26.2:4.116.1` (JUnit 6 at runtime), sqlite-jdbc 3.49.1.0/3.53.4.0,
RoaringBitmap 1.6.23, zstd-jni 1.5.7-17, JMH 1.37, foojay resolver 1.0.0, Minotaur 2.10.0,
hangar-publish 0.1.4, forbidden-apis 3.10, Testcontainers 2.0.5, Configurate 4.2.0
(`YamlConfigurationLoader` sets `processComments(false)`), bStats 3.2.1, Prometheus client 1.9.0.

jqwik 1.10.1: Maven Central metadata, its POM (JUnit Platform 1.14.4) and its published release
notes, including the anti-AI-usage clause. See ADR-0004.

### Ecosystem (published material only)

* **CoreProtect** — `docs.coreprotect.net` (commands, permissions, configuration, API v10–v13,
  database migration), Modrinth/Hangar/Spigot listings, release notes, `maven.playpro.com`
  metadata, GitHub issue *titles*. No source, no DDL, no jar or POM fetched.
* **WorldEdit / FAWE** — `worldedit.enginehub.org` and `docs.enginehub.org` javadoc,
  `maven.enginehub.org` metadata, the FAWE gitbook and its published javadoc
  (`IBatchProcessor`, `ProcessorScope`, `Settings.EXTENT.ALLOWED_PLUGINS`), Maven Central metadata.
  Search results linking to their source files were not opened. FAWE's `allowed-plugins` semantics
  are **not** documented publicly and will be established by observation on a dev server.
* **LogBlock, Prism, Spyglass, NoriTrace, MineTracer and other current loggers** — listing pages and
  READMEs only.
* **"Oasis"**, named in the build brief as the closed-source ClickHouse competitor, **could not be
  found** by search on 2026-09-19: no Modrinth, Hangar, Polymart or web listing matched. On
  2026-09-20 the owner supplied an analysis written from Oasis's public README, kept verbatim in
  [`docs/competitive/oasis.md`](../competitive/oasis.md). **The README's URL is still missing**, so
  this remains a summary rather than a cited primary source, and every performance figure in it is
  a claim Oasis makes about itself which their own documentation says is not reproducible from
  their repository. None of those figures may be published or compared against a Trace measurement.

### Name and command collision check (2026-09-19)

Modrinth API (`/v2/search`, `/v2/project/trace`), Hangar API (`/api/v1/projects/Trace`), Spiget,
CurseForge, `repo1.maven.org/maven2/in/gravaxis/`, GitHub search; published command references for
EssentialsX, WorldEdit, FAWE, CoreProtect, Prism, LogBlock, CMI, mcMMO, LuckPerms, Towny,
GriefPrevention, Multiverse, WorldGuard, AuthMe, Geyser, Floodgate, spark, TAB, Lands, Citizens.
Results and consequences: ADR-0002.

### Licence and governance references

`gnu.org/licenses/gpl-3.0.txt`, `apache.org/licenses/LICENSE-2.0.txt`, `developercertificate.org`,
Contributor Covenant 2.1, the SPDX `GPL-3.0-interface-exception` template, Sonatype Central Portal
namespace-verification documentation, Hangar resource guidelines and Modrinth content rules.

### Paper API shapes read from the pinned jar (2026-09-20)

`javap` against `io.papermc.paper:paper-api:26.2.build.126-stable` in the Gradle cache, for
`org.bukkit.event.block.BlockPlaceEvent` (constructor arity and argument order, used by the
integration scenario's place path) and `org.bukkit.Bukkit#isOwnedByCurrentRegion` (overloads). The
jar is the primary source; nothing here came from a tutorial or a memory of an older API.

### Folia region boundary, measured (2026-09-20)

The M2 integration scenario needs two areas that a regionised server owns separately, and the
distance was measured rather than assumed. Running on Folia 26.2 build 7 with the generated default
`threaded-regions.grid-exponent: 4`, a region task owning chunk 0,0 was asked
`Bukkit.isOwnedByCurrentRegion(world, d, d)` for d in {8, 16, 24, 32, 40, 64, 128, 256, 512}:
everything up to 64 answered "same region", everything from 128 answered "other region". The
scenario uses 128 chunks. An earlier version used 40 and tested one region twice while claiming two.
This is an observation of our own test server, reproducible by re-running the scenario.

### CoreProtect storage density, first observation (2026-09-21)

The owner supplied a real CoreProtect database for SPIKE-2: 12,349,562,880 bytes, standard `co_*`
schema, zero free pages.

**Why reading it is permitted.** The clean-room rule forbids opening CoreProtect's *source*. This is
a database their software produced, observed as a black box, which is what the approved plan named
as SPIKE-2's input from the outset. No CoreProtect jar was downloaded and no source was read.

**Privacy.** The file holds real player data, including IP addresses in `co_session`. Only aggregates
were read: row counts per table, and per-object page and byte totals from the `dbstat` virtual
table. No row was selected. Nothing from the file is committed, and no figure below identifies
anyone.

**Method.** `pragma page_count`/`page_size`/`freelist_count`, `select count(*)` per table, and
`select name, count(*), sum(pgsize) from dbstat group by name`, over a read-only connection. Note
that `dbstat` is **not** available in Python's bundled SQLite but **is** available in the pinned
`org.xerial:sqlite-jdbc:3.49.1.0` — the same driver Paper bundles — so the in-repo tool needs no new
dependency. The full page walk took 686 s.

**Observed.** 133,529,532 rows in `co_block`; every other table combined holds 34,000 rows, so the
database is 99.97% block logging by row count. Bytes attributable to block logging, 12,343,799,808,
are 99.95% of the file: 4,243,337,216 in the table and 8,100,462,592 across its three indexes. That
works out at 31.78 bytes of row data per logged block change and 92.44 bytes including the indexes,
which cost 2.9 times the rows they index.

**Status: not publishable.** These figures came from a throwaway script, not from a committed tool,
so by this project's own rule they do not yet exist. They are recorded here as an observation and a
method, to be reproduced by the measurement task M3 builds. Nothing derived from them may reach the
README, the documentation site or any comparison until that task is committed and its output is in
`benchmarks/results/`. A comparison also needs the Trace side measured the same way, and a statement
of what is counted on each side: the two databases hold different data, and a ratio between them
licenses far less than it appears to.

### SQLite transaction scope checked for M3 (2026-09-21)

Read SQLite's official [WAL documentation](https://www.sqlite.org/wal.html) and
[ATTACH documentation](https://www.sqlite.org/lang_attach.html). Attached databases
in WAL mode have per-database atomicity, not atomicity across the set. The current
store's cross-database publication comments therefore cannot justify crash safety.
The M3 execution plan requires a single-database hot/manifest transaction boundary.

### M3 pinned API verification (2026-09-21)

Used `javap` on the pinned Paper and Folia API jars for Bukkit's world lookup,
region/async scheduler accessors, RegionScheduler.execute/runDelayed and
AsyncScheduler.runNow/runDelayed. Paper's World chunk-loading signatures were
also inspected. Used `javap` on sqlite-jdbc 3.49.1.0 for
SQLiteErrorCode.SQLITE_CONSTRAINT and its public code field. No forbidden project
source or source DDL was read.

### SPIKE-2 repeatable baseline (2026-09-21)

The committed measurement task at e643f099dcdc has now produced the aggregate-only
report in `benchmarks/results/density/2026-09-21-e643f099dcdc/density.json`.
It uses schema name/type/ownership metadata internally (no DDL), row counts and
grouped dbstat page totals; it never selects player rows. Report-local aliases
replace arbitrary object names. The source database is not committed.
The Trace fixtures in this baseline use the pre-M3 implementation; ADR-0018 and
`benchmarks/STORAGE-DENSITY.md` explain counted components and comparison limits.

## How to add to this file

One row or bullet per source: what you read, where, and when. If a fact is load-bearing — a version
pin, an API contract, a licence claim — cite the primary source, not a summary of it.
## 2026-09-21 — M3 density follow-up

The synthetic Trace side was rerun after implementation commit e82a6dda926e, with
a clean tree, through `:benchmarks:storageDensity` without a private input argument.
Its aggregate report is `benchmarks/results/density/2026-09-21-e82a6dda926e/density.json`.
The same dbstat reader was used. This does not repeat or replace the private-input
walk recorded in `benchmarks/results/density/2026-09-21-e643f099dcdc/density.json`.
No dataset equivalence or production savings ratio is established.

## 2026-09-21 — bounded maintenance APIs

Verified with javap against the pinned sqlite-jdbc 3.49.1.0 jar:
org.sqlite.ProgressHandler.setHandler(Connection,int,ProgressHandler),
clearHandler(Connection), and protected progress() returning int. Verified the
existing SnakeYAML 2.2 jar's Yaml(SafeConstructor), load(String), dumpAsMap(Object),
SafeConstructor(LoaderOptions) and LoaderOptions.setAllowDuplicateKeys(boolean).
SnakeYAML remains server-provided compileOnly, as ADR-0005 specifies. No new
Minecraft API call is introduced by consumer-thread maintenance scheduling.
