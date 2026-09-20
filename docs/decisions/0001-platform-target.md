# ADR-0001: Target Paper 26.2 stable, not the newest Minecraft release

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0

## Context

The build brief assumed Minecraft 26.2 was current. It is not, and the gap matters:

* Minecraft Java **26.3** was released 2026-09-15 (Mojang `version_manifest_v2`,
  `latest.release = 26.3`), requiring Java 25.
* Paper for 26.3 exists only on the **ALPHA** channel. The newest STABLE Paper is **26.2 build 126**
  (`fill.papermc.io/v3/projects/paper/versions/26.2/builds?channel=STABLE`, 2026-09-20).
* **Folia has no 26.3 at all.** Its newest build is 26.2 build 7 on the **BETA** channel
  (2026-08-25), and `dev.folia:folia-api` stops at `26.2.build.7-beta`.
* WorldEdit 7.4.5, FAWE 2.15.x and CoreProtect 24.0 also stop at 26.2 or earlier, so the M5/M9
  integrations have nothing to test against on 26.3 either.
* `paper-api`'s `maven-metadata.xml` lists `<latest>` and `<release>` as a stray
  `26.3-pre-2.build.0-alpha` that `fill` returns 404 for. Any dynamic version selector can pick it.

Folia build 7 is also cut from an older Paper commit than Paper build 126, so paper-api methods
added in between exist at compile time but not on the Folia server we claim to support.

## Decision

1. Compile against **`io.papermc.paper:paper-api:26.2.build.126-stable`**, pinned literally in
   `gradle/libs.versions.toml`. No `+`, no `latest.release`, no `RELEASE`.
2. Declare **`api-version: '26.2'`** (quoted — Paper parses it as a string, and an unquoted `26.10`
   would become the float `26.1`) and **`folia-supported: true`**.
3. Pin the test servers in `gradle/servers.properties` — Paper 26.2 build 126, Folia 26.2 build 7 —
   with their sha256, which the launcher verifies before executing anything.
4. Run a `compileAgainstFoliaApi` check in `check`: the same sources are recompiled against
   `dev.folia:folia-api:26.2.build.7-beta`, so anything Folia's build lacks fails the build rather
   than a player's server.
5. Move to 26.3 when Paper marks it STABLE **and** Folia ships it; track both in Renovate, and treat
   the 26.3 ALPHA line as a non-blocking CI signal until then.
6. The repository owner accepted the Minecraft EULA for the throwaway servers the build starts. The
   launcher writes `eula=true` into each disposable run directory and nowhere else.

## Observed, 2026-09-20

Booting the M0 plugin (`api-version: '26.2'`) on **Paper 26.3 build 26 (ALPHA)** succeeded: the
plugin enabled and the harness reported PASS. That confirms experimentally what the source scan only
suggested — `paper-plugin.yml` has no upper-bound API-version check, unlike legacy `plugin.yml`. So
a 26.2-targeted build is not locked out of the newer line; the reason to wait is Folia and the
integration plugins, not the descriptor.

## Consequences

* Trace targets one Minecraft version behind the newest release for a few weeks each cycle. That is
  what "stable Paper" means right now; Paper 26.2 went from release to STABLE in about six weeks.
* Folia coverage is against a BETA build. The Modrinth/Hangar listings must say exactly that rather
  than implying a stable Folia.
* MockBukkit only publishes `mockbukkit-v26.2`, so the unit-test harness moves when we move.

## Alternatives considered

* **Target 26.3 alpha now.** Rejected: no Folia, no WorldEdit/FAWE/CoreProtect builds, and an
  alpha server is not something to measure benchmarks on.
* **Support both lines at once.** Rejected for M0–M2: two API baselines double the test matrix
  before there is anything to test.
