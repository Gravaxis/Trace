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

The off-server maintenance runner uses slf4j-api 2.0.17, matching the pinned Paper
API POM (local Gradle cache). javap verified NOPLogger.NOP_LOGGER in that jar. It
loads StoreConsumer directly without Bukkit; no server implementation is bundled
into the benchmark.

## 2026-09-21 — incremental maintenance observations

IncrementalMaintenanceTest exercises the pinned sqlite-jdbc 3.49.1.0 through JDBC:
TRUNCATE checkpoint with busy_timeout=0 returns a busy status while a real manifest
read transaction is pinned; retry after release reports success and leaves an empty
WAL. This is a tested behavior of the pinned engine, not an inferred hard deadline.
The new scheduled tick measurements reuse TickRecorder's already pinned Paper/Folia
event API. No new Minecraft API or forbidden project source was needed.

The final synthetic SPIKE-2 refresh at fcc56df records both explicit full-window
and incremental-prefix sealing through the same aggregate-only DensityReader.
Its generated report is `benchmarks/results/density/2026-09-21-080557-fcc56dff4830/density.json`.
Private input was explicitly empty and was not reopened. The preceding reader
output at 17c3b6e came from a wrapper invocation that failed configuration-cache
serialization; STORAGE-DENSITY.md records that distinction.

## 2026-09-21 — M4 design API inventory

Read local repository sources for the capture, journal, dictionaries, storage,
rollback and server harness before writing `docs/design/m4-capture-plan.md`.
Ran JDK 25.0.4 javap against both pinned Gradle-cache jars:
`io.papermc.paper:paper-api:26.2.build.126-stable` and
`dev.folia:folia-api:26.2.build.7-beta`.

Verified the candidate block-event classes and accessors named in the plan:
BlockMultiPlaceEvent.getReplacedBlockStates, piston extend/retract getBlocks,
BlockFromToEvent.getToBlock, growth/form/spread/fade getNewState (including
inheritance), BlockBurnEvent.getIgnitingBlock, block/entity explosion blockList,
and EntityChangeBlockEvent.getBlock/getBlockData. Inspected InventoryClickEvent,
InventoryDragEvent and InventoryMoveItemEvent, PlayerJoinEvent/PlayerQuitEvent,
EntitySpawnEvent/EntityDeathEvent and HangingPlaceEvent/HangingBreakEvent.
EntityDeathEvent implements Cancellable in both pinned jars.

Also verified Block/BlockState.getBlockData, BlockData.getAsString,
ItemStack.serializeAsBytes/deserializeBytes, Entity.getUniqueId/getScheduler and
Bukkit.isOwnedByCurrentRegion overloads. Existence of Entity.getScheduler does
not authorize its use under the repository's current scheduler restriction.
These are signature checks, not proof of ownership, allocation, event ordering,
NBT fidelity or natural server behavior. Internal NBT/state handles and protocol
client packet contracts remain unverified; no new dependency was selected.
No forbidden project source or private database was read.

## 2026-09-21 — real loopback client gate

Inspected the already downloaded pinned Paper and Folia implementation jars with
javap: ProtocolInfo.DetailsProvider/Details/PacketVisitor, handshake/login/configuration
protocol templates, packet constructors and STREAM_CODEC fields for login,
configuration finish/known packs/code-of-conduct, keepalive/ping, teleport ack,
player-loaded/client-tick-end, player actions, use-item-on and chunk-batch receipt.
Inspected the Paper packet type tables and player-action/use-item-on bytecode;
packet ids come from each runtime's own table, not copied constants. No new
dependency or NMS compile dependency was added. The client uses these codecs over
a real loopback Socket from the harness process, not a server connection shortcut.
This deliberately does not independently validate the server's wire encoding.

Both API jars were checked for teleportAsync, ownership overloads, inventory access,
setGameMode, setItemInMainHand, Bukkit.getPort and the event APIs. The initial Paper
gate failed because its fixture assumed an air break emitted no event: the pinned
server emits a natural creative-mode break event and Trace rejects it at tick end.
The corrected gate asserts that branch after the individual action, separately
from cancellation and same-tick reversion. Successful generated evidence is the
authority for reported results, not this development observation.

## 2026-09-21 — owner-supplied Spyglass suggestion

Read only the rendered README at https://github.com/medievalrp-net/Spyglass after
the owner supplied a screenshot recommending bounded queues. No implementation,
build file or schema was opened. The README did not establish the suggested
bounded-queue overflow policy; its performance statements were not adopted or
compared. The owner chose to finish the client gate, then bound M4 payload queues.
Trace's queue design follows its own nonblocking-producer and explicit-loss
requirements, not an inferred contract from the screenshot.

## 2026-09-22 — bounded payload integration on pinned runtimes

The in-repository `:benchmarks:payloadIntegrationEvidence` task exercised the pinned
Paper and Folia builds identified by the copied `servers.properties`, using their
existing runtime SQLite driver. No new external dependency or Minecraft API was
introduced. The synthetic payload scenario submits already-persisted dictionary
ids through the actual runtime queue and consumer, checks exact stored fields and
bytes, forces oversized rejection, and calls RollbackService to require payload
refusal. Existing real-client and rollback scenarios run separately, serially.
The source-labelled evidence is
[here](../../benchmarks/results/payload-integration/2026-09-22-095619-cba62e8af72b/complete.json).
This observation does not establish natural payload capture, independent client
wire compatibility, NBT fidelity, power-loss safety or payload performance.

## 2026-09-22: confirmation ownership precheck

Verified with JDK 25 javap against both pinned API jars, Paper
`26.2.build.126-stable` and Folia `26.2.build.7-beta`:
`Bukkit.isOwnedByCurrentRegion(World, int, int)` and
`World.isChunkLoaded(int, int)` exist. `BlockCaptureListener.stateAt` checks the
chunk coordinates before its world read. This signature check does not alone
prove runtime ownership behavior; the serial client regression remains required.

The same javap check verified `World.addPluginChunkTicket(int, int, Plugin)` on
both API pins. Paper implementation bytecode from the pinned server jar confirms
the ownership overload forwards chunk coordinates to `TickThread.isTickThreadFor`
and `CraftWorld.isChunkLoaded` delegates to `ServerChunkCache.isChunkLoaded`.
The playerless resume fixture initially failed with a capture gap; retaining its
chunks with plugin tickets made the targeted Paper regression pass. The fixture
now asserts readable confirmation and publication before attempting rollback.
This is a fixture correction, not a guarantee that production chunks stay loaded.

The follow-up `:benchmarks:confirmationEvidence` run against clean source
`a49e3ad075b0` passed the existing real-client, block rollback and cancellation/
resume scenarios on both copied server pins. The
[generated result](../../benchmarks/results/confirmation/2026-09-22-142522-a49e3ad075b0/complete.json)
also retains exact normal/cold allocation and JMH gate reports, plus the named
core and consumer tests. New confirmation edge branches have unit/consumer proof;
their natural server triggers are not established by these existing scenarios.
