import java.io.File
import trace.build.AllocationGateTask
import trace.build.BenchmarkReportTask

plugins {
  id("trace.java-conventions")
}

description =
  "JMH microbenchmarks, the report that collects a run into benchmarks/results/, and the gates " +
    "that fail the build when a measured property regresses."

// JMH's own Gradle plugin has had no release since January 2025 and does not officially support
// Gradle 9, so the source set and the runner are wired by hand. That is a few lines, and it removes
// a moving part from the thing whose whole job is to be trustworthy.
val jmh: SourceSet = sourceSets.create("jmh")

dependencies {
  // The harness in the main source set drives the real capture path, so it needs the module that
  // holds it. Gate P1 measuring a stand-in was the defect this dependency removes.
  implementation(project(":trace-core"))
  implementation(project(":trace-storage-sqlite"))
  implementation(project(":trace-paper"))
  implementation(libs.slf4j.api)
  implementation(libs.sqlite.jdbc)
  "jmhImplementation"(libs.jmh.core)
  "jmhAnnotationProcessor"(libs.jmh.generator)
  "jmhImplementation"(project(":trace-core"))
  // The benchmark and the exact allocation test must measure the same code, so both compile
  // against this module's main source set.
  "jmhImplementation"(sourceSets.main.map { it.output })
}

val jmhResultsFile = layout.buildDirectory.file("jmh/jmh-result.json")
val allocationReportFile = layout.buildDirectory.file("jmh/allocation-gate.json")

val jmhRun = tasks.register<JavaExec>("jmhRun") {
  group = "verification"
  description = "Runs the JMH benchmarks with the GC profiler and writes JSON."
  classpath = jmh.runtimeClasspath
  mainClass.set("org.openjdk.jmh.Main")
  outputs.file(jmhResultsFile)
  javaLauncher.set(
    javaToolchains.launcherFor {
      languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
    }
  )
  // Modest defaults so the gate can run on every pull request; a full run passes -Ptrace.jmh.args.
  val extraArgs = providers.gradleProperty("trace.jmh.args").orElse("")
  val include = providers.gradleProperty("trace.jmh.include").orElse(".*")
  val resultPath = jmhResultsFile.map { it.asFile.absolutePath }
  argumentProviders.add(
    CommandLineArgumentProvider {
      buildList {
        add(include.get())
        addAll(listOf("-prof", "gc"))
        addAll(listOf("-rf", "json"))
        addAll(listOf("-rff", resultPath.get()))
        addAll(listOf("-foe", "true"))
        extraArgs.get().split(' ').filter { it.isNotBlank() }.forEach { add(it) }
      }
    }
  )
  doFirst {
    File(resultPath.get()).parentFile.mkdirs()
  }
}

val checkAllocationGate = tasks.register<AllocationGateTask>("checkAllocationGate") {
  group = "verification"
  description = "Fails if a benchmark that must not allocate reports any allocation (gate P1)."
  dependsOn(jmhRun)
  jmhResults.set(jmhResultsFile)
  report.set(allocationReportFile)
  // Benchmarks whose contract is zero allocation per operation. The empty baseline is in the list
  // so the measurement floor of JMH itself is recorded next to the real number.
  zeroAllocationBenchmarks.set(
    listOf(
      "in.gravaxis.trace.bench.BaselineBenchmark.emptyBaseline",
      "in.gravaxis.trace.bench.CaptureBenchmark.captureRejectedAtTickEnd",
      "in.gravaxis.trace.bench.CaptureBenchmark.capturePublishedToRing",
    )
  )
  // The control proves the profiler is awake. See ADR-0009 for why the threshold is not literally
  // zero and why the exact test below is the gate of record.
  controlBenchmarks.set(listOf("in.gravaxis.trace.bench.BaselineBenchmark.allocatingReference"))
  thresholdBytesPerOp.set(0.01)
}

// The exact half of the gate: whole bytes, counted per thread, run twice — once normally and once
// with the optimiser's escape analysis off, so a pass cannot depend on scalar replacement.
val testWithoutEscapeAnalysis = tasks.register<Test>("testWithoutEscapeAnalysis") {
  group = "verification"
  description = "Runs the allocation tests with escape analysis and C2 disabled."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  jvmArgs("-XX:-DoEscapeAnalysis", "-XX:TieredStopAtLevel=1")
  filter { includeTestsMatching("*ExactAllocationTest") }
}

tasks.named("check") {
  dependsOn(testWithoutEscapeAnalysis)
}

val harnessBuildDir = project(":trace-test-harness").layout.buildDirectory
val resultsDirectory = layout.projectDirectory.dir("results")

fun BenchmarkReportTask.configureCommon() {
  repositoryRoot.set(rootProject.layout.projectDirectory.asFile.absolutePath)
  gradleVersion.set(gradle.gradleVersion)
  serverPins.set(rootProject.layout.projectDirectory.file("gradle/servers.properties"))
  resultsRoot.set(resultsDirectory.asFile.absolutePath)
  readmePath.set(rootProject.layout.projectDirectory.file("README.md").asFile.absolutePath)
}

val benchmarkReport = tasks.register<BenchmarkReportTask>("benchmarkReport") {
  group = "verification"
  description = "Collects this run into benchmarks/results/<date>-<commit>/ and rewrites the README table."
  configureCommon()
  mode.set("write")
  dependsOn(checkAllocationGate, ":trace-test-harness:benchmarkScenarios")
  jmhResults.set(jmhResultsFile)
  allocationGateReport.set(allocationReportFile)
  scenarioResults.from(harnessBuildDir.dir("scenario-results"))
  crashReports.from(harnessBuildDir.dir("crash-results"))
}

tasks.register<BenchmarkReportTask>("verifyReadmeTable") {
  group = "verification"
  description = "Fails if the README's benchmark table does not match the newest committed results."
  configureCommon()
  mode.set("verify")
}

tasks.register("benchmark") {
  group = "verification"
  description = "Runs every benchmark and writes a results directory."
  dependsOn(benchmarkReport)
}

// Private inputs are neither copied nor fingerprinted by Gradle. Reports contain aggregates only.
tasks.register<JavaExec>("storageDensity") {
  group = "verification"
  description = "Measures SQLite page accounting using aggregates only; boots no server."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.StorageDensity")
  workingDir(rootProject.layout.projectDirectory)
  javaLauncher.set(javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
  })
  val privateInput = providers.gradleProperty("trace.density.input").orElse("")
  val output = layout.buildDirectory.file("density/report.json")
  val scratch = layout.buildDirectory.dir("density/work")
  val densityResultsRoot = rootProject.layout.projectDirectory.dir("benchmarks/results/density").asFile.absolutePath
  outputs.file(output)
  outputs.upToDateWhen { false }
  argumentProviders.add(CommandLineArgumentProvider {
    listOf(privateInput.get(), output.get().asFile.absolutePath,
      scratch.get().asFile.absolutePath,
      densityResultsRoot)
  })
  maxHeapSize = "1g"
}

tasks.register<JavaExec>("maintenanceCosts") {
  group = "verification"
  description = "Measures synthetic maintenance under real capture/consumer load; no server."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.MaintenanceCosts")
  workingDir(rootProject.layout.projectDirectory)
  maxHeapSize = "1g"
  args(layout.buildDirectory.dir("maintenance").get().asFile.absolutePath,
    rootProject.layout.projectDirectory.dir("benchmarks/results/maintenance").asFile.absolutePath)
  outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("m3Evidence") {
  group = "verification"
  description = "Runs M3 correctness gates and generates a commit-labelled evidence directory."
  dependsOn(":trace-paper:test", ":trace-storage-sqlite:test", ":trace-core:test",
    ":trace-test-harness:integrationTest", ":trace-test-harness:storageMaintenanceTest",
    ":trace-test-harness:scheduledMaintenanceTest", ":trace-test-harness:crashRollbackTest",
    ":trace-test-harness:crashJournalTest")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.M3Evidence")
  workingDir(rootProject.layout.projectDirectory)
  args(rootProject.layout.projectDirectory.asFile.absolutePath)
  outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("clientCaptureEvidence") {
  group = "verification"
  description = "Runs serial real-client gates and records their revision and scope."
  dependsOn(":trace-test-harness:clientCaptureTest", ":trace-test-harness:test")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.ClientEvidence")
  workingDir(rootProject.layout.projectDirectory)
  args(rootProject.layout.projectDirectory.asFile.absolutePath)
  outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("payloadQueueEvidence") {
  group = "verification"
  description = "Runs fresh bounded payload tests and records their revision and scope."
  dependsOn(":trace-core:payloadQueueTest")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.PayloadQueueEvidence")
  workingDir(rootProject.layout.projectDirectory)
  args(rootProject.layout.projectDirectory.asFile.absolutePath)
  outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("payloadIntegrationEvidence") {
  group = "verification"
  description = "Fresh payload failure, replay and serial pinned runtime evidence."
  dependsOn(":trace-core:payloadJournalTest", ":trace-storage-sqlite:payloadIntegrationTest",
    ":trace-paper:payloadConsumerTest", ":trace-test-harness:payloadHandoffTest",
    ":trace-test-harness:clientCaptureTest", ":trace-test-harness:integrationTest")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.PayloadIntegrationEvidence")
  workingDir(rootProject.layout.projectDirectory)
  args(rootProject.layout.projectDirectory.asFile.absolutePath)
  outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("m3MaintenanceEvidence") {
  group = "verification"
  description = "Records unit and serial maintenance-only evidence after a scoped storage change."
  dependsOn(":trace-paper:test", ":trace-storage-sqlite:test", ":trace-core:test",
    ":trace-test-harness:storageMaintenanceTest", ":trace-test-harness:scheduledMaintenanceTest")
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("in.gravaxis.trace.bench.M3Evidence")
  workingDir(rootProject.layout.projectDirectory)
  args(rootProject.layout.projectDirectory.asFile.absolutePath, "maintenance")
  outputs.upToDateWhen { false }
}
