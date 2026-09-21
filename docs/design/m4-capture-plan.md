# M4 capture plan and adversarial review — 2026-09-21

Status: design, before implementation. M3 is complete under its final audit;
none of the gates below has passed merely because this plan exists. This is the
next deliverable agreed after M3, not a declaration that M4 is complete.

The owner authorized proceeding from the ADRs and pinned APIs without supplying
the separately referenced build-spec matrix. The coverage matrix below is a
proposed minimum, not a recovered specification or an exhaustive promise about
every way Minecraft or another plugin can change the world. Each expansion must
name its event path and proof before it receives a supported status.

## Verified starting point

Paths below are relative to the repository root. Java paths use these prefixes:

* core: `trace-core/src/main/java/in/gravaxis/trace/core/`
* paper: `trace-paper/src/main/java/in/gravaxis/trace/`
* storage: `trace-storage-api/src/main/java/in/gravaxis/trace/storage/`
* sqlite: `trace-storage-sqlite/src/main/java/in/gravaxis/trace/storage/sqlite/`
* harness: `trace-test-harness/src/main/java/in/gravaxis/trace/harness/`

These identifiers were read from source, not inferred from their names:

| Existing file and identifier | Current boundary |
|---|---|
| core `record/RecordKind.java`, `Cause.java` | BLOCK, GAP, HEARTBEAT; UNKNOWN, BREAKING, PLACING, ROLLBACK. Persisted ids must not be reused |
| core `capture/CaptureService.java`: captureBlockChange, confirmStaged, publish | Primitive staging, tick-end state comparison, four-word ring publication. No payload transport; sidecar is written as zero |
| paper `capture/BlockCaptureListener.java`: onBlockBreak, onBlockPlace, stateAt, onJoin | Break/place only; material ids; onJoin calls actor registration synchronously |
| paper `dictionary/BlockStateDictionary.java`: idOf, materialOf, load | Material-granularity persisted dictionary, not complete block properties |
| paper `dictionary/ActorDictionary.java`: register, save | Registration can write a file; moving it outside capture does not move it outside a tick thread |
| core `journal/JournalWriter.java`: appendEvents, appendGap, writeFrame | No event-plus-payload write API |
| core `journal/JournalReader.java`: FrameHandler.frame, replay | Callback exposes longs, losing arbitrary trailing bytes if simply reused for blobs |
| paper `runtime/TraceRuntime.java`: recover, recoverRings | Recovery routes event frames into store.append; adding a frame type alone will not replay it |
| paper `pipeline/StoreConsumer.java` | Drains rings, journals events, appends RecordBatch; sole consumer owns journal writes |
| storage `EventStore.java`: append, putBlob, attachBlob, blobAt | Event and payload attachment are separate operations under ADR-0017 |
| sqlite `SqliteEventStore.java`: append | Event rows and applied watermark commit together; exclusions prevent resurrecting removed rows |
| paper `rollback/RollbackService.java`: run, applyChunk | Folds scanned rows as blocks without dispatching on kind; resolves materialOf and writes setType |
| harness `scenarios/HarnessPlayers.java`: fireBlockBreak, fireBlockPlace | Proxy player and explicit callEvent, not a connected client |
| build-logic `src/main/kotlin/trace/build/ServerScenarioTask.kt`: run | Starts and awaits one server; no external client orchestration |
| `trace-test-harness/build.gradle.kts`: registerScenario, testServerLock | Existing pinned platform matrix and shared service for serial server execution |

Public candidate APIs were inspected with javap in both pinned API jars; see
provenance. Internal state/NBT handles and client packet contracts are not yet
verified. No unavailable method is assumed below.

## Order and release gates

1. Establish the real-client gate and exact capture semantics, retaining synthetic
   tests as useful isolated tests. Define persisted kinds, causes and payload
   completeness without allocating their ids speculatively.
2. Implement atomic journal-to-store event/payload publication and loss handling,
   with shared contract and process-kill gates before a listener can use it.
3. Implement complete block-state capture and capability reporting; migrate old
   material ids without reinterpreting existing history. Then add block causes.
4. Add block entities and containers on the proven payload path.
5. Add entity and session history, then run the complete coverage/proof matrix.

Rollback safety is a prerequisite in steps 3–5: unknown kinds and incomplete
payloads must not reach the block fold. This is a narrow safety change, not an
excuse to implement M6 preview, undo UI, adaptive budgets or relighting now.

## A. Real client and capture semantics

**Files.** Extend ServerScenarioTask/ServerRuntime in build-logic, the harness
build file and `TraceHarness.java` scenario registration. Proposed new files:
build-logic `trace/build/ProtocolClientTask.kt` and harness
`scenarios/ClientCaptureScenario.java`. Keep HarnessPlayers for explicitly
synthetic tests. A client dependency, if used, belongs only to the harness runner,
must be pinned and verified for the target protocol before selection. A second
server is never part of the client fixture.

**Design.** The scenario prepares a known world on owning region threads, signals
readiness, and the runner connects a loopback client to the disposable offline
server. Client actions, server-observed player identity, actual world state and
stored rows are separate observations. Session readiness and per-action barriers
replace sleeps as correctness conditions. The runner owns client/server cleanup,
including timeout and failure. It uses the same TestServerService for both pinned
platforms and never overlaps another server Gradle invocation.

**Obstacles.** No protocol implementation is currently selected. Login,
configuration, compression, keepalive and action acknowledgement cannot be
guessed from an older Minecraft protocol. First verify a compatible client or the
pinned server packet implementation, document provenance, then write the task.
Do not widen the proxy to make this test appear to have a real player.

**Proof.** Require actual connection/join, accepted break and place, a cancelled
action, an unchanged attempt and a same-tick revert. Compare full expected rows
and world states; assert recorded, rejected and cancelled branches individually.
For Folia require server ownership checks for distinct test areas. Disconnect
must be observable and runner failures must fail the Gradle task.

**Will not prove.** Online authentication, hostile clients, all clients, all
protection plugins or production performance. Synthetic tests do not become
real-client evidence by sharing a result collector.

## B. Atomic event, dictionary and payload pipeline

**Files.** Change core journal JournalFrames/Writer/Reader and their
`JournalRoundTripTest`; add a proposed core `journal/CaptureEnvelope.java` codec.
Extend storage EventStore/RecordBatch with a proposed `appendCaptured` operation;
implement in SqliteEventStore and test in inherited EventStoreContract. Extend
StoreConsumer, TraceRuntime recovery, CaptureService/CaptureLoss and proposed
core `capture/PayloadArena.java`. Extend StorageCrashWorker/StorageCrashTest.
Proposed names are new files/methods, not claims that they exist today.

**Design.** One versioned capture envelope carries the event words, required
dictionary additions and a versioned payload containing before/after content.
The existing single blob reference per event can name this envelope payload;
before and after must not compete for the same reference. The journal frame is
complete before appendCaptured publishes rows, dictionaries, payload references
and the applied watermark in one manifest transaction. Do not implement it as
public append + putBlob + attachBlob calls: those commit separately.

Keep byte length exact, including non-long-aligned payloads. Frame CRC and LSN
remain the integrity rules; salt changes remain run boundaries. Payload-version,
length and kind validation are semantic decoding: unsupported valid content must
refuse startup/replay rather than be treated as a torn tail and truncated. A format
upgrade must prevent an older writer from opening and silently skipping new data.
Legacy event frames remain readable. Retention/purge exclusions apply to the whole
envelope, including attachments; replay cannot recreate a removed payload reference.

Reserve bounded producer payload capacity before publication, with explicit
ownership until consumer acknowledgement. A transient slot index is never a
durable identity. Generation/reuse, multiple producers, ring failure, cancellation
and partial copy each need a defined release/loss branch. Store no live Bukkit
objects in consumer queues. Region-owned snapshot work precedes any async encoding
that can safely use an immutable snapshot. No disk I/O or waiting on the producer.
If payload capture fails, do not publish a row that implies complete payload
capture: record counted loss bounds and refuse unsafe rollback.

Dictionary publication is part of this dependency, not just a cache optimization.
Replace tick-thread ActorDictionary.save with ordered consumer persistence;
an event may not become recoverable before its dictionary identity. Full-state
dictionary additions follow the same rule. Unknown/exhausted ids must not be
silently truncated by the packed representation.

**Proof.** Shared contract compares exact event fields, payload version/bytes,
attachment identity and watermark across reopen, replay twice, seal/compact,
purge/retention and conflicting replay. Inject failure after row insertion but
before attachment/watermark and assert none is committed. Kill real child JVMs
after payload reservation, journal publication, inside store transaction and
after commit; require announced branches, repeated reopen, and either complete
identity/payload or a covering gap. Force arena exhaustion and reuse with distinct
payloads; a stale token must never bind to a new payload. Legacy replay, unknown
version refusal, odd-length bytes, torn tails and salt transitions are separate
tests. Test dictionary failure and replay order as well as happy-path payloads.

**Will not prove.** Power loss, arbitrary instruction crashes, payload fidelity,
bounded native I/O or secure deletion. Copy/serialization allocation is not
measured; no zero-allocation assertion may be extended beyond its actual gate.
New reachable encoder paths need normal and cold allocation gates with branch
counters before they can be advertised as allocation-free.

## C. Complete block states and causes

**Files.** Change BlockStateDictionary, BlockCaptureListener, CaptureService,
RecordKind/Cause and RollbackService. Proposed paper files
`capability/StateInterner.java`, `capability/CapabilityProbe.java` and
`capture/BlockCauseListener.java`; new packages require package-info.java.
Keep internal access behind ADR-0007 capability interfaces; verify signatures and
runtime behavior on both actual pinned servers before relying on them.

**Design.** Persist explicit state properties plus data version, with stable Trace
ids. Preserve legacy material ids as legacy fidelity, never reinterpret them as
complete states. A public BlockData path exists but is not evidence of a
zero-allocation implementation. Probe failure must retain honest capability
status and refuse correctness-dependent work. Check region ownership before world
reads; catching an exception after an illegal read is not ownership verification.

Proposed minimum cause matrix:

| Family | Verified candidate API | Principal edge to prove |
|---|---|---|
| Player break/place | Existing listeners; BlockMultiPlaceEvent.getReplacedBlockStates | Multi-block placement exactly once; cancellation/no-op/revert |
| Pistons | BlockPistonExtendEvent/BlockPistonRetractEvent.getBlocks | Source and destination union, overlap and vacated cells |
| Fluids | BlockFromToEvent.getToBlock | Destination actually changes, source attribution not invented |
| Growth/form/spread/fade | BlockGrowEvent/BlockFormEvent/BlockSpreadEvent/BlockFadeEvent.getNewState (including inheritance) | Inherited event handling does not duplicate records |
| Fire | BlockBurnEvent.getIgnitingBlock | Nullable attribution and cancelled burns |
| Explosions | BlockExplodeEvent/EntityExplodeEvent.blockList | Final affected list, rejected destruction and actor uncertainty |
| Entity block changes | EntityChangeBlockEvent.getBlock/getBlockData | Falling blocks and actor identity distinguished from player actions |

This list needs an inventory pass for further causes such as buckets, structures,
fertilization, decay, moisture, ignition, interaction-driven properties and portal
creation before “every cause” can pass. Their APIs are not asserted here. Direct
plugin writes without events and M5 mass-edit hooks are not covered by this table.

**Proof.** Complete properties survive capture, replay, compaction and query.
Include same-material property changes, multiple changes at one position in a
tick, round trips to the initial state and overlapping causes. Pin how ADR-0014
net tick-end observation represents those sequences; do not label it a complete
sub-tick history. Each family needs a natural server trigger and cancelled/no-op
arm, exact before/after/actor/cause/kind assertions, and ownership checks on Folia.
Keep the old basic block rollback regression and test unsupported-history refusal
before any world writes.

**Will not prove.** All arbitrary plugin writes, sub-tick causality, cross-version
DataFixer correctness or NBT fidelity. Listener allocation and production cost are
not measured. Merely adding an enum and firing its event is not family coverage.

## D. Block entities and containers

**Files.** Proposed paper `capability/BlockEntityCodec.java`,
`capture/BlockEntityCapture.java`, `capture/ContainerCaptureListener.java`;
core payload codec under a new `payload/` package. Wire capabilities in
TraceRuntime and report them through existing TraceCommand. Extend BlobStorageTest,
EventStoreContract and harness scenarios; gate RollbackService before exposure.

**Design.** Capture before content while it still exists on its owning region;
confirm after content there too. Freeze snapshots before handing work off.
Payload includes codec version, game data version, before/after values and an
explicit completeness state. Unsupported NBT is not an empty container. Probe
internal NBT handles from actual pinned implementation jars; javap has not yet
established those handles. ItemStack.serializeAsBytes/deserializeBytes exist in
both APIs but do not establish arbitrary block-entity support or allocation cost.

Containers compare actual before/after inventories, not click intentions. Cover
InventoryClickEvent, InventoryDragEvent and InventoryMoveItemEvent separately.
Inventory transfer needs source and destination identity, ownership and joint
completeness; neither live Inventory nor ItemStack may be read asynchronously.
Double-container identity, cursor/hotbar changes, automation, destruction and
multiple viewers need explicit transaction semantics. If cross-region ownership
prevents an atomic observation, record the limitation/loss, never fabricate an
atomic transfer from observations made at unrelated times.

**Proof.** Named contents, item metadata, empty versus missing, same-block content
changes, cancelled transfer, shift click, drag, hopper movement and destruction.
Compare payloads after replay and maintenance. Force probe failure, oversized
payload, serialization failure and unavailable owner; require explicit outcomes
and refusal. Destructive rollback involving a block entity must refuse before
world mutation until safe restoration and no-duplication gates exist.

**Will not prove.** A complete container rollback engine, all block-entity types,
all plugin inventories, cross-version upgrades or loss-free overload. Any later
claim of restoration needs world/item checks including absence of spilled items;
opaque blob equality alone cannot support it.

## E. Entities and sessions

**Files.** Proposed paper `capture/EntityCaptureListener.java`,
`capture/SessionCaptureListener.java`, payload codecs; change ActorDictionary,
TraceRuntime, RecordKind/Cause and rollback kind dispatch. Add corresponding
harness scenarios and shared persistence tests. No new public API surface merely
for testing private implementation; retain the harness module dependency rules.

**Design.** Entity identity uses persistent UUID, never a runtime numeric entity
id or block coordinate. EntitySpawnEvent, EntityDeathEvent, HangingPlaceEvent and
HangingBreakEvent are verified candidate events, not an exhaustive lifecycle map.
Distinguish death/destruction from unload, teleport and disappearance; cancellation
is real even for EntityDeathEvent in these jars. Observe on the owning thread.
The repository instruction currently permits region/async/global-region
schedulers only; do not silently adopt Entity.getScheduler merely because javap
shows it. Resolve mobile-entity follow-up ownership within that constraint, or
raise the conflict before implementation.

Session join/quit records are history-only, with no fabricated block mutation.
Use immutable player/session identity and a run identity. Process death leaves
an open session rather than inventing a quit timestamp. Do not collect IPs,
addresses, chat or commands as an accidental consequence of implementing sessions.
Proposed initial scope is lifecycle history, with other history-only families
requiring an explicit coverage decision. No private database is needed.

**Proof.** Actual join/disconnect/rejoin, restart with an unfinished session,
duplicate callbacks, actor dictionary persistence before dependent events.
Actual entity lifecycle plus cancelled/undone actions; compare UUID and payload
through reopen and compaction. Assert history-only rows cannot enter block
rollback. Refuse an unsupported destructive selection before any partial writes.

**Will not prove.** Entity resurrection, cross-region atomicity, every lifecycle
source, entity rollback or a privacy-erasure product. These are separate claims.

## Self-attack and resulting design changes

| Attack | Consequence and required repair before implementation |
|---|---|
| Append event first, attach payload later | Crash exposes apparently complete history without contents. Use one transaction and one replayable envelope |
| Let appliedLsn skip an earlier independent blob frame | Replay loses the payload forever. Envelope includes dependencies; no untracked independent watermark |
| Read variable payload through long[] callback | Tail bytes disappear. Add exact-length byte framing/decoding and odd-length tests |
| Reuse producer sidecar ids after drain | Old events acquire new payloads. Explicit generation/ownership; persisted references use full event identity |
| Async snapshot to protect tick time | Bukkit state is read off-owner and may already be gone. Snapshot on owner; only detached content crosses threads |
| Register an actor on join as today | Synchronous file writes remain on tick thread. Consumer-ordered dictionary persistence is prerequisite |
| Add ENTITY/SESSION to current store | Rollback folds them as block changes. Kind-aware preflight/refusal precedes enabling producers |
| Use material equality to confirm capture | Facing/waterlogging-only mutations disappear. Full-state interning and property-only tests first |
| Treat MONITOR as immutable outcome | Later listeners or server rejection can change the result. Actual post-observation remains required |
| Use tick-end state as exact per-event causality | Multiple actors/events at one position get misleading intermediate states. Specify net observation and test collisions |
| New payload format accepted by old binary | Old recovery may ignore unknown types and advance. Version refusal must precede any writer mutation |
| Just keep the old journal comments | JournalFrames/Reader still describe three validity rules despite ADR-0013. Correct stale documentation with protocol work; never restore salt rejection |
| Pass tests by constructing events | Does not prove natural server behavior. Label synthetic scope and require real triggers in acceptance gates |
| Enumerate a few event classes and call it full capture | Roadmap has no exhaustive checked-in matrix. Maintain explicit covered/unsupported/pending rows, then audit completeness |

## Completion evidence

Implementation proceeds one slice at a time with a failing correctness test where
practical, then unit/build/module/Folia compilation checks. Capture changes also
run the exact normal/cold and JMH allocation gates against the reached branches.
Run spotlessApply before build and before each signed-off commit; never race it
with compilation. No push.

All server, crash and benchmark tasks run serially on this machine. Extend the
existing MeasurementRun/evidence tooling for M4; do not hand-create result
directories or relabel old runs. Commit raw reports with explicit revision and
scope before publishing measurements. Throughput, latency, payload density and
listener allocation for M4 are currently **not measured**.

M4 remains incomplete until the final coverage matrix, shared persistence/crash
gates, full-content server assertions and explicit negative branches pass on both
pinned platforms. M3's final-audit limits remain in force. Contended-chunk and
overlapping rollback work stay with M6 unless an M4 safety change touches those
paths, in which case their relevant regressions become prerequisites here.
