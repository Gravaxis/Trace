# ADR-0005: Configuration is a commented YAML template, rendered rather than serialized

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (implemented in M2)

## Context

The brief requires YAML mapped to typed objects, `config-version` as the first key, migrations with
a backup, a refusal to start on a config newer than the code, and — the hard part — a file that is
**rebuilt from the packaged template on every start**: operator values preserved, new keys inserted
in place *with their comments*, removed keys dropped, and a log of what changed.

Configurate is the usual answer and Paper already ships it (`configurate-yaml:4.2.0`), but its YAML
loader **cannot write per-key comments**: the 4.2.0 loader sets `processComments(false)` and emits
only a header. The open PR for comment support has been unmerged since 2023. A config file whose
comments cannot be regenerated fails the requirement above.

There is also a collision to design around: since 2026-09-15 several Dans-Plugins read a server-wide
`plugins/trace/config.yml` (key `enabled`) as the switch for an unrelated usage-reporting service.
On Windows and macOS that is the *same file* as Trace's `plugins/Trace/config.yml`.

## Decision

1. The packaged, fully commented `config.yml` template is the source of truth for structure,
   ordering and comments. On start, Trace parses the operator's file for **values only**, then
   re-renders the template with those values substituted. Keys the build no longer has disappear
   because the template no longer contains them; new keys appear in place, with their comments.
2. Parsing uses the SnakeYAML that Paper already provides (`org.yaml:snakeyaml:2.2`, `compileOnly`),
   so Trace ships no YAML library of its own.
3. Values bind to Java records with explicit parsers per type (durations like `200ms`, sizes like
   `32MiB`, enums), and **all** validation errors are reported at once. On any error the running
   configuration is kept and the file is refused — never half-applied.
4. `config-version` is the first key. A file newer than the code aborts startup; an older one runs
   the migration chain after copying `config.yml.bak-v<old>-<timestamp>`.
5. **Foreign keys are preserved, not deleted**, and their presence is logged with a pointed warning
   naming the shared-file problem. Deleting another plugin's `enabled` key could silently re-enable
   someone's usage reporting; that is not ours to do.
6. The configuration reference page is generated from the same annotated template, so it cannot
   drift from what ships.

## Consequences

* We own the merge logic (a few hundred lines, heavily unit-tested with golden files) instead of
  inheriting it from a library.
* Operators keep their formatting only where the template allows it; comments always come from the
  build. That is the intended trade: the file documents the current build, not the one it was
  written by.
* If Configurate ships YAML comment support, or ConfigLib becomes compelling, this is replaceable
  behind the same typed-records boundary.

## Alternatives considered

* **Configurate object mapper.** Rejected: header-only comments.
* **ConfigLib (`de.exlll:configlib-yaml`).** Records plus per-field comments, but it would add a
  second YAML implementation to relocate, and it loses comments inside collections.
* **HOCON.** Comment support, but a format Minecraft operators do not expect. The brief calls a
  novel format a gratuitous tax, and it is right.
