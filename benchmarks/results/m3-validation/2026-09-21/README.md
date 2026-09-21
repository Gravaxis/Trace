# M3 validation evidence

Raw output from the source in this report's introducing commit. Base commit and
working-tree status are in environment.json. No private database rows or files
are included. Synthetic harness state only. Servers ran serially.

Reproduce from that source:

```
./gradlew build
./gradlew :trace-test-harness:integrationTest
./gradlew :trace-test-harness:crashRollbackTest
./gradlew :trace-test-harness:storageMaintenanceTest :trace-test-harness:crashJournalTest
./gradlew :trace-storage-sqlite:test :benchmarks:test :benchmarks:testWithoutEscapeAnalysis :benchmarks:checkAllocationGate
```

All listed gates passed. The purge scenario initially failed because it selected
the rollback request actor instead of the synthetic player's captured actor. Its
changed-history assertion caught that defect; retained results are the corrected
run. No test assertion was relaxed.

Allocation retains the complete JMH output, gate evaluation and exact test
windows. Exact gating uses the quietest measured window, per ADR-0009, not a claim
that every sample is zero. The unit suite shared the JMH invocation; these results
are an allocation regression gate, not a controlled latency comparison.

Server results retain exercised branch details. Crash rollback reports use the
generic rig's loss fields as unused sentinels; paired iteration results establish
checkpoint, compaction, resumed work and world verification. Journal crash reports
require a lossy iteration per platform; an individual no-loss iteration is
inconclusive. Child-JVM storage crash tests assert their ready phase before kill.

This does not establish the outstanding proof listed under M3 in ROADMAP.md.
