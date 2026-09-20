# Oasis

**Source.** Supplied by the repository owner on 2026-09-20, written from Oasis's public README
(2026-09). It answers the question ADR-0002 and `provenance.md` left open: an independent search on
2026-09-19 found no Modrinth, Hangar, Polymart or web listing for a block logger called Oasis, so
nothing about it had been recorded. **A link to the README is still missing** — see "Owner inputs"
below.

**Status of the numbers in it: unverified, and they stay that way.** Every measurement below is a
claim Oasis makes about itself, and the analysis notes that their test scripts and raw results are
deliberately kept outside their repository. None of it may appear in Trace's README, documentation
site, or listings, and none of it may be compared against a Trace measurement, because a number we
cannot reproduce is not a baseline. If a comparison is ever published, it has to come from our own
harness running both, on stated hardware, with the results committed.

**What this file is for.** It is a design and positioning input — the requirements at the bottom are
worth treating as requirements — not a source of facts about the world.

**One conflict to resolve before it spreads.** The analysis proposes AGPL-3.0 as the answer to a
closed-source competitor. Trace's licence is locked the other way:
[ADR-0006](decisions/0006-licence-and-module-layout.md) records Apache-2.0 for `trace-api` and
GPL-3.0-or-later for everything else, with AGPL considered and rejected because its §13 burdens
honest server owners while §5 already covers the paywalled-fork threat. That decision is not being
changed on the strength of a competitive note; if the owner wants AGPL, it is a deliberate relicence
decision with consequences for every contributor and it needs to be taken explicitly.

**Owner inputs this raises.**

1. The URL of the Oasis README, so `provenance.md` can cite a primary source rather than a summary.
2. Whether the AGPL question above is being reopened. Default: no, ADR-0006 stands.

---

The owner's analysis follows verbatim.

---

# Oasis — competitive analysis (from their public README, 2026-09)

## What Oasis is
Closed-source, free-to-use Paper block logger. Requires an external ClickHouse server.
Targets Paper 26.2 / Java 25. Author previously forked CoreProtect twice and has a merged
commit in CoreProtect master (a lookup memory fix).

## Their architecture, as disclosed
1. **Streaming rollback.** The server never holds the edit list. Edits stream out of ClickHouse
   a few chunks at a time. Memory is flat regardless of job size (307MB @ 27k edits,
   368MB @ 8M edits). THIS IS THE CENTRAL CLAIM AND IT IS CORRECT.
2. **Write-ahead journal on disk before the DB.** Every edit fsync'd to a local journal within
   milliseconds, flushed to ClickHouse every ~1000ms. Survives kill -9 with at most one moment lost.
   DB down => journal keeps growing, retry every 30s, nothing lost.
3. **Resumable rollbacks.** Rollback state is checkpointed per chunk; a crash mid-rollback resumes
   on next start (351 unfinished chunks replayed in 3.7s).
4. **Adaptive tick budget.** 5–30ms of each tick spent placing blocks, backing off when ticks tighten.
   Worst tick 11.7ms average during a 50M-edit rollback.
5. **Chunk-ordered storage.** "The history is stored in the order the question asks for." Lookups
   15–29ms flat at every dataset size. Rollback planning = list of chunks to visit in 398ms over 50M edits.
6. **Content-addressed blob dedup.** A 2MB item stored once; 10,000 hopper moves of it added only
   403 bytes. Massive win for container logging.
7. **2.2 bytes/edit on disk** (ClickHouse columnar compression + dictionary encoding).
8. **Word-based command grammar.** user / last / older / near / in / block / cause / kind / not / page,
   any order, tab-complete with hover docs, did-you-mean on typos. No `u:` `t:` `r:` flag slop.
9. **Rich chat output.** Click coord to teleport, hover sign to read lines, [read] opens a book,
   rolled-back rows shown struck through, 8 per page with prev/next links, boss bar for progress.
10. **Taxonomy split: `kind` (blocks/containers/entities/items/books/chat/commands/sessions) x
    `cause` (~30 verbs).** Genuinely good information architecture. Worth adopting the *shape*.
11. FAWE integration via `extent.allowed-plugins`. TNT attributed through ignition chains.
12. Confirm gate above 100k edits. /undo reverses the last operation. Per-player cancel.
13. Public `oasis-api` Maven artifact; ships an agent skill for the API.

## Their claimed CoreProtect benchmarks (8M block FAWE cube, identical hardware)
- CoreProtect @2GB: FAILED — 23.3s frozen tick, watchdog fired, nothing reached the DB.
- CoreProtect @16GB: 154.5s to log, 15.2s rollback, 86.7s restore, 2,308MB retained, 6,903MB peak.
- Oasis @2GB: 8.4s log, 6.7s rollback, 5.6s restore, 368MB retained, 668MB peak.
NOTE: unverifiable — they state the test scripts and raw results are kept OUTSIDE the repo.

## Where they left the door open — our wedge
1. **CLOSED SOURCE.** Their stated reason is fear that an open licence would let a paywalled
   competitor absorb the design. AGPL-3.0 answers that fear directly. We are the open one.
   Their own README says they'll "look at an open source license again" — they are hedging.
2. **MANDATORY CLICKHOUSE.** They explicitly and repeatedly abandon every server that can't run a
   ClickHouse VPS ("not for your shitty Aternos server"). That is most of the market, and they
   are proud of excluding it. If we hit near-Oasis numbers on an EMBEDDED default engine, with
   ClickHouse/Postgres as an optional scale tier, we take both markets. This is THE hard
   engineering problem of our project and it is the whole differentiator.
3. **NO GUI.** Words-only. The Discord ask that started this project was for a chat/inventory GUI
   and a rollback builder. Modern Paper Dialog API + clickable components covers this.
4. **No Folia story.** Unmentioned. Region-threaded servers are the growth segment.
5. **Unverifiable benchmarks.** Test scripts deliberately kept out of the repo. We publish a
   reproducible harness in-repo, in CI, with raw results. "Run it yourself" beats "trust me".
6. **No stated virtual-thread story** despite Java 25.
7. **No automatic retention/purge scheduling** (they call it a feature; for small servers it isn't).
8. **No documentation site** — one enormous README. We ship real versioned docs.

## What we must match or beat, non-negotiable
- Constant memory rollback (streaming, never materialise the edit list).
- Crash-safe journal-first write path.
- Resumable, cancellable, tick-budgeted rollback application.
- Chunk-clustered storage layout so cuboid queries are sequential range scans.
- Content dedup for container/item payloads.
- Full-fidelity restores: chest contents, sign front/back text, item frames, armour stands,
  killed mobs with their NBT, enchantments, custom names. (CoreProtect's silent
  null-on-serialization-failure is the specific failure they call out — we must fail loudly.)
- Word-based command grammar with tab completion and did-you-mean, PLUS the GUI they don't have.

---

## How this lines up with what Trace has built so far

Traceable to the repository, not to the analysis:

| Their requirement | Where Trace stands on 2026-09-20 |
|---|---|
| Constant-memory streaming rollback | `MutationCursor` streams; `RollbackService` folds one chunk at a time and never materialises the edit list. Memory is not yet measured under load — no number is claimed. |
| Crash-safe journal-first write path | Built ([ADR-0013](decisions/0013-journal-and-recovery.md)). Proven against `kill -9` only for the harness's own log so far; the rig does not yet shoot at Trace's journal. |
| Resumable rollback | Not built. Listed as an unmet M2 item in [ROADMAP.md](../../ROADMAP.md). |
| Tick-budgeted, cancellable application | Applies per region with verification; no adaptive budget, no cancel. M6. |
| Chunk-clustered storage | Built: Morton chunk key, `WITHOUT ROWID` shards keyed `(world, chunk, key)`, keyset paging, no `OFFSET`. |
| Content dedup for payloads | Not built. M4. |
| Full-fidelity restores, failing loudly | Not built; block entities are explicitly reported as "contents not captured in this build" before a rollback runs, which is the loud-failure shape. M4. |
| Word grammar plus a GUI | `/trace` exists with two subcommands. Grammar is M7, dialogs are M8. |
| Embedded default with no external service | The whole premise; SQLite tier is what M2 and M3 are. |
| Reproducible published benchmarks | Built in M1: `./gradlew benchmark` writes a results directory, and `verifyReadmeTable` fails the build if the README table is hand-edited. |
