import java.util.Properties
import trace.build.CrashInjectionTask
import trace.build.ServerScenarioTask
import trace.build.TestServerService
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml

plugins {
  id("trace.paper-plugin-conventions")
}

description =
  "Companion plugin that drives integration tests and benchmark scenarios on a real server, " +
    "plus the tasks that boot pinned Paper and Folia builds to run them."

dependencies {
  // Trace itself is on the server; the harness only talks to its published API.
  compileOnly(project(":trace-api"))
}

paperPluginYaml {
  name = "TraceTestHarness"
  main = "in.gravaxis.trace.harness.TraceHarness"
  apiVersion = "26.2"
  foliaSupported = true
  description = "Test and benchmark driver for Trace. Never ship this on a production server."
  authors.add("Gravaxis")
  dependencies {
    // Trace must be up before a scenario runs, and the harness needs its API on the classpath.
    server("Trace", PaperPluginYaml.Load.BEFORE, true, true)
  }
}

val tracePlugin = configurations.dependencyScope("tracePlugin")
val tracePluginJar = configurations.resolvable("tracePluginJar") {
  extendsFrom(tracePlugin.get())
}

dependencies {
  add(tracePlugin.name, project(mapOf("path" to ":trace-paper", "configuration" to "pluginJar")))
}

val pins = providers
  .fileContents(rootProject.layout.projectDirectory.file("gradle/servers.properties"))
  .asText
  .map { text -> Properties().apply { load(text.reader()) } }

// A pin can be overridden on the command line, which is how CI tests an unreleased server line:
//   ./gradlew smokePaper -Ptrace.paper.version=26.3 -Ptrace.paper.build=23 -Ptrace.paper.sha256=...
fun pin(key: String) = providers.gradleProperty("trace.$key")
  .orElse(pins.map { it.getProperty(key) ?: error("missing pin: $key") })

val launcher = javaToolchains.launcherFor {
  languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get().toInt()))
}

// Server jars are cached outside the project tree and never committed.
val serverCache = File(gradle.gradleUserHomeDir, "caches/trace-servers")

// One test server at a time: see TestServerService for why.
val testServerLock =
  gradle.sharedServices.registerIfAbsent("traceTestServer", TestServerService::class) {
    maxParallelUsages.set(1)
  }

// Benchmarks run on a deliberately small heap: numbers measured with a large one say nothing about
// the servers this plugin is for. The size is recorded in every result.
val benchmarkJvmArgs = listOf("-Xms1G", "-Xmx1G", "-XX:+UseG1GC")

fun registerScenario(
  taskName: String,
  server: String,
  scenarioName: String,
  serverPort: Int,
  params: Map<String, String> = emptyMap(),
  collectResult: Boolean = false,
  heapArgs: List<String> = listOf("-Xms512M", "-Xmx1G"),
) = tasks.register<ServerScenarioTask>(taskName) {
  group = "verification"
  description = "Runs the '$scenarioName' scenario on a pinned $server server."
  serverProject.set(server)
  port.set(serverPort)
  mcVersion.set(pin("$server.version"))
  buildNumber.set(pin("$server.build").map { it.toInt() })
  sha256.set(pin("$server.sha256"))
  contact.set(providers.gradleProperty("trace.contact").orElse("unknown"))
  usesService(testServerLock)
  scenario.set(scenarioName)
  scenarioParams.set(params)
  timeoutSeconds.set(420)
  javaExecutable.set(launcher.map { it.executablePath.asFile.absolutePath })
  jvmArgs.set(heapArgs)
  pluginJars.from(tracePluginJar, tasks.named("shadowJar"))
  runDirectory.set(layout.buildDirectory.dir("test-servers/$taskName"))
  if (collectResult) {
    resultCopy.set(layout.buildDirectory.file("scenario-results/$taskName.json"))
  }
  serverCacheDirectory.fileValue(serverCache)
}

val smokePaper = registerScenario("smokePaper", "paper", "boot", 25591)
val smokeFolia = registerScenario("smokeFolia", "folia", "boot", 25592)

// The M2 definition of done: capture, store, roll back, on both platforms.
val integrationPaper = registerScenario("integrationPaper", "paper", "block-break-rollback", 25596)
val integrationFolia = registerScenario("integrationFolia", "folia", "block-break-rollback", 25597)

// An interrupted rollback continues from where it stopped, also part of the M2 definition of done.
val resumePaper = registerScenario("resumePaper", "paper", "rollback-resume", 25598)
val resumeFolia = registerScenario("resumeFolia", "folia", "rollback-resume", 25599)

val purgePaper = registerScenario("purgePaper", "paper", "storage-purge", 25602)
val purgeFolia = registerScenario("purgeFolia", "folia", "storage-purge", 25603)
val quarantinePaper = registerScenario("quarantinePaper", "paper", "storage-quarantine", 25604)
val quarantineFolia = registerScenario("quarantineFolia", "folia", "storage-quarantine", 25605)
tasks.register("storageMaintenanceTest") { dependsOn(purgePaper,purgeFolia,quarantinePaper,quarantineFolia) }

val scheduledPaper = registerScenario("scheduledPaper", "paper", "storage-scheduled", 25606)
val scheduledFolia = registerScenario("scheduledFolia", "folia", "storage-scheduled", 25607)
listOf(scheduledPaper, scheduledFolia).forEach { task -> task.configure {
  traceConfig.set("config-version: 1\nmaintenance-enabled: true\nmaintenance-interval: 1s\nmaintenance-budget: 5s\nretention-age: 0ms\n")
} }
tasks.register("scheduledMaintenanceTest") { dependsOn(scheduledPaper, scheduledFolia) }

tasks.register("integrationTest") {
  group = "verification"
  description = "Breaks blocks, stores the history and rolls it back, on Paper and on Folia."
  dependsOn(integrationPaper, integrationFolia, resumePaper, resumeFolia)
}

tasks.register("smokeTest") {
  group = "verification"
  description = "Boots Trace on the pinned Paper and Folia builds and asserts it comes up."
  dependsOn(smokePaper, smokeFolia)
}

// --- Benchmark scenarios --------------------------------------------------------------------

val churnParams = mapOf("ticks" to "200", "blocksPerTick" to "256")

val benchmarkPaper = registerScenario(
  taskName = "benchmarkPaper",
  server = "paper",
  scenarioName = "block-churn",
  serverPort = 25593,
  params = churnParams,
  collectResult = true,
  heapArgs = benchmarkJvmArgs,
)

val benchmarkFolia = registerScenario(
  taskName = "benchmarkFolia",
  server = "folia",
  scenarioName = "block-churn",
  serverPort = 25594,
  params = churnParams,
  collectResult = true,
  heapArgs = benchmarkJvmArgs,
)

val crashTest = tasks.register<CrashInjectionTask>("crashTest") {
  group = "verification"
  description = "Kills a writing server with SIGKILL at seeded random points and checks what survived."
  serverProject.set("paper")
  mcVersion.set(pin("paper.version"))
  buildNumber.set(pin("paper.build").map { it.toInt() })
  sha256.set(pin("paper.sha256"))
  contact.set(providers.gradleProperty("trace.contact").orElse("unknown"))
  usesService(testServerLock)
  writeScenario.set("crash-write")
  verifyScenario.set("crash-verify")
  scenarioParams.set(mapOf("rate" to "5000", "forceEvery" to "1"))
  port.set(25595)
  iterations.set(providers.gradleProperty("trace.crash.iterations").map { it.toInt() }.orElse(2))
  minKillDelayMillis.set(750L)
  maxKillDelayMillis.set(4_000L)
  // Fixed so that a failure can be replayed exactly; override with -Ptrace.crash.seed=...
  seed.set(providers.gradleProperty("trace.crash.seed").map { it.toLong() }.orElse(20260920L))
  javaExecutable.set(launcher.map { it.executablePath.asFile.absolutePath })
  jvmArgs.set(listOf("-Xms512M", "-Xmx1G"))
  timeoutSeconds.set(300)
  pluginJars.from(tracePluginJar, tasks.named("shadowJar"))
  runDirectory.set(layout.buildDirectory.dir("test-servers/crashTest"))
  report.set(layout.buildDirectory.file("crash-results/crash-paper.json"))
  serverCacheDirectory.fileValue(serverCache)
}

/**
 * The durability gate: kill a server while Trace is capturing, restart, and check that every event
 * it lost is inside a recorded gap.
 *
 * Unlike `crashTest`, which shoots at the harness's own forced log and so proves the rig works,
 * these shoot at Trace. `minLossyIterations` is what keeps them honest: a kill that lands between
 * ticks loses nothing, and a set of iterations that never lost anything has verified nothing.
 */
fun registerJournalCrashTest(
  taskName: String,
  server: String,
  serverPort: Int,
) = tasks.register<CrashInjectionTask>(taskName) {
  group = "verification"
  description = "Kills a server mid-capture and checks every lost event is covered by a gap ($server)."
  serverProject.set(server)
  mcVersion.set(pin("$server.version"))
  buildNumber.set(pin("$server.build").map { it.toInt() })
  sha256.set(pin("$server.sha256"))
  contact.set(providers.gradleProperty("trace.contact").orElse("unknown"))
  usesService(testServerLock)
  writeScenario.set("crash-journal-write")
  verifyScenario.set("crash-journal-verify")
  port.set(serverPort)
  iterations.set(providers.gradleProperty("trace.crash.iterations").map { it.toInt() }.orElse(3))
  minLossyIterations.set(1)
  // Long enough for the burst to be well under way, short enough to keep the run quick.
  minKillDelayMillis.set(1_500L)
  maxKillDelayMillis.set(6_000L)
  seed.set(providers.gradleProperty("trace.crash.seed").map { it.toLong() }.orElse(20260920L))
  javaExecutable.set(launcher.map { it.executablePath.asFile.absolutePath })
  jvmArgs.set(listOf("-Xms512M", "-Xmx1G"))
  timeoutSeconds.set(300)
  pluginJars.from(tracePluginJar, tasks.named("shadowJar"))
  runDirectory.set(layout.buildDirectory.dir("test-servers/$taskName"))
  report.set(layout.buildDirectory.file("crash-results/$taskName.json"))
  serverCacheDirectory.fileValue(serverCache)
}

val crashJournalPaper = registerJournalCrashTest("crashJournalPaper", "paper", 25596)
val crashJournalFolia = registerJournalCrashTest("crashJournalFolia", "folia", 25597)

fun registerRollbackCrash(taskName: String, server: String, serverPort: Int) = tasks.register<CrashInjectionTask>(taskName) {
  group = "verification"
  description = "Kills a rollback after an asserted checkpoint, compacts, resumes and checks the world."
  serverProject.set(server)
  mcVersion.set(pin("$server.version"))
  buildNumber.set(pin("$server.build").map { it.toInt() })
  sha256.set(pin("$server.sha256"))
  contact.set(providers.gradleProperty("trace.contact"))
  usesService(testServerLock)
  prepareScenario.set("rollback-crash-prepare")
  writeScenario.set("rollback-crash-write")
  verifyScenario.set("rollback-crash-verify")
  scenarioParams.set(emptyMap())
  port.set(serverPort)
  iterations.set(1)
  minKillDelayMillis.set(100)
  maxKillDelayMillis.set(100)
  seed.set(20260921L)
  javaExecutable.set(launcher.map { it.executablePath.asFile.absolutePath })
  jvmArgs.set(listOf("-Xms512M", "-Xmx1G"))
  timeoutSeconds.set(420)
  pluginJars.from(tracePluginJar, tasks.named("shadowJar"))
  runDirectory.set(layout.buildDirectory.dir("test-servers/$taskName"))
  report.set(layout.buildDirectory.file("crash-results/$taskName.json"))
  serverCacheDirectory.fileValue(serverCache)
}

val crashRollbackPaper = registerRollbackCrash("crashRollbackPaper", "paper", 25600)
val crashRollbackFolia = registerRollbackCrash("crashRollbackFolia", "folia", 25601)
tasks.register("crashRollbackTest") { dependsOn(crashRollbackPaper, crashRollbackFolia) }

tasks.register("crashJournalTest") {
  group = "verification"
  description = "The durability gate on both platforms."
  dependsOn(crashJournalPaper, crashJournalFolia)
}

tasks.register("benchmarkScenarios") {
  group = "verification"
  description = "Every server-side benchmark and durability scenario."
  // The journal crash tasks are here so that one `./gradlew benchmark` produces every piece of
  // evidence the README publishes, including the durability gate, rather than leaving the most
  // important one to a command nobody remembers to run.
  dependsOn(benchmarkPaper, benchmarkFolia, crashTest, crashJournalPaper, crashJournalFolia)
}
