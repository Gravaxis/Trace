# ADR-0002: Keep the name "Trace"; ship one command surface, `/trace`

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (command registration lands in M7)

## Context

"Trace" is a short, common English word, so the brief required a collision check before the first
public commit. Checked on 2026-09-19 against Modrinth, Hangar, SpigotMC (via Spiget), CurseForge,
Maven Central and GitHub.

**Name.** No plugin or mod is published as exactly "Trace". The Modrinth slug `trace`
(`api.modrinth.com/v2/project/trace` → 404), the Hangar project name `Trace`
(`/api/v1/projects/Trace` → 404) and the Maven namespace `in.gravaxis`
(`repo1.maven.org/maven2/in/gravaxis/` → 404) are all unclaimed. Near names exist — EchoTrace,
PlayerTracer, MineTracer (Fabric), Blocktrace (Forge), Ghost Trace, Command Trace, NoriTrace — and
two of them register `/trace`: EchoTrace as its main command (~256 downloads) and PlayerTracer as an
alias (~200 downloads).

**Commands.** From published documentation only (clean room):

| Label | Owner found | Risk |
|---|---|---|
| `/undo`, `/restore` | WorldEdit (`//undo`, `//restore` with single-slash forms) | high |
| `/i` | EssentialsX (`item` alias), CMI preset | high |
| `/inspect` | mcMMO | high |
| `/co`, `/core`, `/coreprotect` | CoreProtect, NoriTrace | high |
| `/trace` | EchoTrace, PlayerTracer | medium |
| `/tr`, `/rollback`, `/rb`, `/lookup`, `/lk`, `/rs` | nothing documented | low |

Paper's Brigadier registration semantics matter here: a **main label overrides** an existing
command, while an **alias is silently skipped** if the label is taken, and a legacy `plugin.yml`
command registered *later* can take a Brigadier label back (`PluginVanillaCommandWrapper` inherits
`canBeOverriden() == true`). `trace:<label>` always works regardless.

## Decision

1. **Keep the name Trace.** No rename; the namespace is clear.
2. **One command surface: `/trace <subcommand>`, with `/tr` as the short alias** — the shape
   CoreProtect uses with `/co`. Trace registers no bare `/rollback`, `/restore`, `/undo`,
   `/inspect`, `/i` or `/lookup` labels, so it cannot take a command out from under WorldEdit,
   EssentialsX or mcMMO. (This narrows the brief's §9.2 table, at the owner's direction.)
3. Register the command tree from `JavaPlugin#onEnable`, not from the bootstrapper: handlers
   registered there run after every plugin has enabled, which is what keeps the bare `/trace` label.
4. Log, at startup, any alias Paper refused to register — the returned `Set<String>` from
   `Commands.register` says which — and point the operator at `commands.yml` aliases and the
   `trace:` namespace.
5. Permission root stays `trace.`; nothing else uses it.
6. Owner actions, outside what the build can do: reserve the Modrinth slug, the Hangar project name,
   and verify the `in.gravaxis` namespace via a DNS TXT record on `gravaxis.in`.

## Consequences

* Muscle memory from CoreProtect transfers (`/co i` → `/trace i`), and the `/co` compatibility alias
  planned for M7 still only registers when no real CoreProtect is present.
* On the few servers running EchoTrace or PlayerTracer, Trace wins the bare `/trace` label at
  startup and those plugins keep their namespaced form. That is worth stating in the docs.
* A plugin that registers commands dynamically after startup can still take `/trace`; the startup
  log and `trace:trace` are the mitigation.

## Alternatives considered

* **Bare top-level labels as the brief's table listed.** Rejected: hijacking `/undo` from WorldEdit
  is a serious regression for builders, and a silently-skipped alias is a confusing half-feature.
* **Rename.** Not on the table — §0 of the brief settles the name; disambiguation is the answer.
