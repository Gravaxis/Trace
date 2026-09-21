# M3 remaining work: ordered plan and self-review

2026-09-21. Execute in order; this is not a completion claim.

## 1. Crash proof

Verified identifiers: SqliteEventStore.open/migrateHot, StorageCrashWorker.main,
StorageCrashTest.killedRewriteRecoversBeforeAndAfterPublication, CaptureService,
CaptureLoss.open, and TraceRuntime.open's persistent-loss recovery block.
Add a package-private open probe around the migration transaction's copy and
commit. Add child-process exhaustion using real CaptureService; extract the
existing loss-to-gap recovery block into storage-api CaptureRecovery (new), shared
with runtime. Assert the ready branch before forceful kill, persisted format before
recovery, rows/watermark after two reopens, and OVERFLOW bounds covering actual
rejected capture time. Does not prove power loss or every instruction boundary.
Attack: a generic CRASH_WINDOW gap could hide failure; accept only OVERFLOW from
the extracted production recovery method. A clean close could force the marker;
kill the child without close. A migration test could test new-store creation;
require an existing format-2 fixture and observe format before reopening.

## 2. Bounded maintenance and scheduling

Verified identifiers: SqliteEventStore.rewrite/writeShard, EventStore.compact,
StoreConsumer.run/maybeSeal, TraceRuntime.open/close, ADR-0005 configuration.
Add explicit maintenance budgets/cancellation and bounded candidate selection,
typed template-backed configuration, and a consumer-thread maintenance schedule.
Do not run overlapping writers or Bukkit tick-thread I/O. Retention remains
disabled unless configured. Assert completed, deferred, cancelled, and disabled
branches; a timer being installed is not proof of a completed pass.
Attack: a deadline checked only before a huge SQL statement is no latency bound;
check row loops and use the pinned SQLite progress callback for long native work.
Cancellation before publication must retain all inputs and no removal gap. A
cooperative budget cannot bound an operating-system disk stall; state that limit.

## 3. Shared contracts

Verified EventStoreContract.open/append/scanAll/planFor/oracle and Events fixtures.
Extend this existing contract with complete-field compaction/suffix preservation,
purge replay exclusion/refusal, cutoff boundaries and durable blob references.
Keep backend-specific corruption and process machinery in SQLite tests.
Attack: counts alone miss wrong content; compare full records where readable and
assert removal/refusal branches independently. Does not prove a future backend
until that backend runs the contract.

## 4. Reproducible costs

Verified benchmarks StorageDensity, DensityReader, build.gradle.kts JavaExec tasks.
Add a synthetic maintenance measurement and a generated evidence collector (new),
which create their own commit-labelled result directories and environment metadata.
Measure actual work/cancellation, elapsed time, sampled heap, concurrent capture
backlog and lost-event counters; fail when the intended branch was not exercised.
Use a constrained heap and no concurrent server tasks. Commit tool before results.
Attack: sampled heap is not allocation or a proven peak; distinguish it. Synthetic
producer rates are fixture parameters, not server throughput. No private input or
production latency claim. A blocked consumer can overflow; report it rather than
discard the run. No performance threshold will be fabricated after seeing results.

## 5. Completion audit

Reconcile ROADMAP.md, ADR-0016/0019, AGENTS.md historical defects and this plan
against executed gates. Run build/module/format checks and serial server regression
including an actual scheduled maintenance pass. Preserve all remaining limits;
M3 stays in progress if its definition of done is unmet. No M4 work, no push.
