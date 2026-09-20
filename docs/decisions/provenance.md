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
  found**: no Modrinth, Hangar, Polymart or web listing matches. Nothing about it is repeated in
  Trace's documentation until the owner supplies a link.

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

## How to add to this file

One row or bullet per source: what you read, where, and when. If a fact is load-bearing — a version
pin, an API contract, a licence claim — cite the primary source, not a summary of it.
