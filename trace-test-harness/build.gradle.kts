import java.util.Properties
import trace.build.ServerSmokeTask
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

fun registerScenario(taskName: String, server: String, scenarioName: String, serverPort: Int) =
  tasks.register<ServerSmokeTask>(taskName) {
    group = "verification"
    description = "Runs the '$scenarioName' scenario on a pinned $server server."
    serverProject.set(server)
    port.set(serverPort)
    mcVersion.set(pin("$server.version"))
    buildNumber.set(pin("$server.build").map { it.toInt() })
    sha256.set(pin("$server.sha256"))
    contact.set(providers.gradleProperty("trace.contact").orElse("unknown"))
    scenario.set(scenarioName)
    timeoutSeconds.set(420)
    javaExecutable.set(launcher.map { it.executablePath.asFile.absolutePath })
    jvmArgs.set(listOf("-Xms512M", "-Xmx1G"))
    pluginJars.from(tracePluginJar, tasks.named("shadowJar"))
    runDirectory.set(layout.buildDirectory.dir("test-servers/$taskName"))
    serverCacheDirectory.fileValue(serverCache)
  }

val smokePaper = registerScenario("smokePaper", "paper", "boot", 25591)
val smokeFolia = registerScenario("smokeFolia", "folia", "boot", 25592)

tasks.register("smokeTest") {
  group = "verification"
  description = "Boots Trace on the pinned Paper and Folia builds and asserts it comes up."
  dependsOn(smokePaper, smokeFolia)
}
