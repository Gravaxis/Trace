# ADR-0021: Cooperative maintenance admission and consumer scheduling

* Status: implemented; unit gates and scheduled Paper/Folia scenario pass
* Date: 2026-09-21
* Milestone: M3

MaintenanceBudget bounds admitted input rows and shard count and supplies a
cooperative deadline/cancellation signal. Compaction may merge an admitted prefix;
retention and purge remain atomic over their complete selection and defer when
the store exceeds admission limits. Oversized leading shards can prevent progress;
status exposes deferral, and operators must deliberately adjust admission limits.
These settings are safety choices, not measured optimal values.

The pinned SQLite ProgressHandler interrupts native VM work; Java row loops also
check cancellation. Callbacks are cleared before rollback/cleanup. No output may
be published after a pre-publication cancellation; after commit the result must
report completion. This cannot interrupt filesystem force, a stalled OS disk call,
or monitor acquisition, and is not a hard real-time guarantee. Seal and full verify
remain separate explicit operations, not covered by these rewrite budgets.

MaintenanceSchedule runs on StoreConsumer after draining and requests. A backlogged
pass defers. It alternates compaction and retention only when retention-age is
positive; default retention is disabled. Shutdown makes the cooperative cancellation
signal true. No Bukkit scheduler is used and no region/world access is introduced.

MaintenanceConfig implements the ADR-0005 template for the current settings: typed
validation, aggregated errors, version refusal, backup on migration, packaged
comments and foreign-key preservation. Scheduling starts only after valid startup
configuration. Hot reload is not provided. `:trace-paper:generateConfigReference`
copies the packaged commented template to docs/generated/config.yml; documentation
site integration remains outside M3.

Tests must assert cancelled-before-publication, oversized/deferred, admitted subset,
disabled, backlog-deferred, completed and retention opt-in branches. The pinned
server scenario must capture two batches and observe consumer-driven compaction;
calling compact directly from the scenario would not test scheduling.
