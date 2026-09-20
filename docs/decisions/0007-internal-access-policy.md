# ADR-0007: Reach server internals through probed MethodHandles, never blindly

* **Status:** accepted
* **Date:** 2026-09-20
* **Milestone:** M0 (probe implemented in M2)

## Context

The brief says `compileOnly paper-api` is enough and no `paperweight-userdev` is needed. Checking
what full-fidelity rollback actually requires (Paper 26.2 sources, allowed reading):

| Need | Public API? |
|---|---|
| Write a block with the revert-placement flags (`818`: clients + known-shape + suppress-drops + skip-on-place + skip-block-entity-side-effects) | **No.** `Block#setBlockData(data, false)` uses `530`, which omits `SKIP_BLOCK_ENTITY_SIDEEFFECTS`, so replacing a container spills its contents — an item-duplication bug in a rollback |
| Block-entity NBT capture and restore | **No.** `CraftBlockEntityState#getSnapshotNBT()` / `loadData(CompoundTag)` are internal |
| Run the DataFixer over an arbitrary NBT blob | **No** (items and entities have it built in; block entities and block-state strings do not) |
| Batch relight | **No.** Starlight's `starlight$serverRelightChunks` is internal |
| Resend chunk data to a single player | **No.** `World#refreshChunk` resends to everyone |
| Zero-allocation block-state id on the tick thread | **No.** `Block#getBlockData()` allocates a wrapper per call |

Reflection is viable: Paper plugin classloaders delegate to the server loader, so
`net.minecraft.*` and `org.bukkit.craftbukkit.*` resolve, and since 26.1 the server is unobfuscated,
so Mojang names are the runtime names. Also relevant: Paper marks `UPDATE_SKIP_POI` (4096) as a
"temporary flag", so numeric flag values must be read, not hard-coded.

## Decision

1. No `paperweight-userdev`, no NMS at compile time. Internals are reached through
   `MethodHandles`/`VarHandles` resolved once at enable, behind a **capability probe**.
2. Every capability is an interface with at least two implementations — the internal fast path and
   a public-API fallback — selected by the probe. Today's list: `BlockSetter` (flagged write),
   `BlockEntityCodec` (raw NBT), `StateInterner` (dense ids), `Relighter`, `ChunkResender`,
   `DataFixerBridge`.
3. **Probe failures are loud, not silent.** A WARN at startup naming the capability, a line in
   `/trace status`, a metric, and — where the fallback is worse in *correctness* rather than only in
   speed, as with container contents — an explicit statement of what may not survive a rollback.
4. Flag constants and method handles are resolved by name at runtime and validated (arity, return
   type) before use; a validation failure degrades that one capability, never the whole plugin.
5. The probe re-runs on every server version change, and CI keeps a non-blocking job on the Paper
   pre-release channel so a broken probe shows up before users find it.

## Consequences

* Trace keeps a plain `compileOnly paper-api` build with no dev bundle and no remapping, which is
  what the brief wanted, while still being able to restore a chest correctly.
* The internal surface is the part most likely to break on a Minecraft update. It is small,
  centralised, and every piece of it degrades to something honest.
* Reading Paper's GPL-3.0 source to learn names, signatures and flag values is fine; Trace's
  implementation is its own. Sources consulted are listed in `provenance.md`.

## Alternatives considered

* **Public API only.** Rejected: container rollback would spill items, and block-entity contents
  could not be captured at all. That is the category's core feature.
* **paperweight-userdev with a dev bundle.** Rejected for now: it buys compile-time typing at the
  cost of a heavier build and version-locked artifacts. Revisit if the handle layer outgrows its
  budget.
