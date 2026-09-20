import com.diffplug.gradle.spotless.SpotlessExtension
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import trace.build.ModuleGraphCheckTask

plugins {
  id("java-library")
  id("com.diffplug.spotless")
  id("net.ltgt.errorprone")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion.toInt()

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(javaVersion))
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(javaVersion)
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
  options.errorprone {
    disableWarningsInGeneratedCode.set(true)
    // Annotation-processor output (JMH's benchmark stubs) is not ours to fix.
    excludedPaths.set(".*/build/generated/.*")
    check("NullAway", CheckSeverity.ERROR)
    option("NullAway:OnlyNullMarked", "true")
  }
}

tasks.withType<Javadoc>().configureEach {
  (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Minecraft 26.x requires Java 25, so that is what we compile for. Tests can be told to run on a
// newer JVM (`-Ptrace.testJvm=26`) to catch forward-compatibility problems before a server operator
// does.
val testJvmVersion = providers.gradleProperty("trace.testJvm")
  .map { it.toInt() }
  .orElse(javaVersion)
val javaToolchainService = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>()

// Property-test knobs reach the forked test JVM, so `-Dtrace.property.cases=...` on the command
// line actually runs the properties harder instead of being silently ignored.
val propertyCases = providers.systemProperty("trace.property.cases")
val propertySeed = providers.systemProperty("trace.property.seed")

tasks.withType<Test>().configureEach {
  if (propertyCases.isPresent) {
    systemProperty("trace.property.cases", propertyCases.get())
  }
  if (propertySeed.isPresent) {
    systemProperty("trace.property.seed", propertySeed.get())
  }
  javaLauncher.set(
    javaToolchainService.launcherFor {
      languageVersion.set(JavaLanguageVersion.of(testJvmVersion.get()))
    }
  )
  useJUnitPlatform()
  testLogging {
    events("failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

tasks.withType<AbstractArchiveTask>().configureEach {
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
}

// The API module is Apache-2.0 so that consumers can link it without licence friction; everything
// else is GPL with the section-7 additional permission. SPDX has no identifier for that permission,
// so the header points at the file rather than inventing an expression a scanner would reject.
val apacheLicensedModules = setOf(":trace-api")

val licenseHeaderText = if (path in apacheLicensedModules) {
  """
  /*
   * Trace - block logging and rollback for Paper servers
   * Copyright (C) 2026 Gravaxis
   *
   * SPDX-License-Identifier: Apache-2.0
   */

  """.trimIndent() + "\n"
} else {
  """
  /*
   * Trace - block logging and rollback for Paper servers
   * Copyright (C) 2026 Gravaxis
   *
   * SPDX-License-Identifier: GPL-3.0-or-later
   * Additional permission under GNU GPL version 3 section 7: see LICENSE-EXCEPTION.md.
   */

  """.trimIndent() + "\n"
}

extensions.configure<SpotlessExtension> {
  java {
    target("src/*/java/**/*.java")
    palantirJavaFormat(libs.findVersion("palantir-format").get().requiredVersion)
    removeUnusedImports()
    trimTrailingWhitespace()
    endWithNewline()
    licenseHeader(licenseHeaderText)
  }
}

// --- Module dependency rules (§4 of the build spec), enforced mechanically ----------------------

val moduleRules: Map<String, Set<String>> = mapOf(
  ":trace-api" to emptySet(),
  ":trace-core" to setOf(":trace-api"),
  ":trace-storage-api" to setOf(":trace-api", ":trace-core"),
  ":trace-storage-sqlite" to setOf(":trace-api", ":trace-core", ":trace-storage-api"),
  ":trace-paper" to setOf(":trace-api", ":trace-core", ":trace-storage-api", ":trace-storage-sqlite"),
  ":trace-test-harness" to setOf(":trace-api"),
)

if (moduleRules.containsKey(path)) {
  val ownPath = path
  val resolved = configurations.named("compileClasspath").flatMap { configuration ->
    configuration.incoming.resolutionResult.rootComponent.map { root ->
      val found = LinkedHashSet<String>()
      val seen = HashSet<String>()
      fun walk(component: ResolvedComponentResult) {
        if (!seen.add(component.id.displayName)) return
        val id = component.id
        if (id is ProjectComponentIdentifier) {
          found.add(id.projectPath)
        }
        component.dependencies
          .filterIsInstance<ResolvedDependencyResult>()
          .forEach { walk(it.selected) }
      }
      walk(root)
      found - ownPath
    }
  }
  val checkTask = tasks.register<ModuleGraphCheckTask>("verifyModuleGraph") {
    group = "verification"
    description = "Fails if this module depends on a module the build spec forbids."
    modulePath.set(ownPath)
    allowedProjects.set(moduleRules.getValue(ownPath))
    actualProjects.set(resolved)
  }
  tasks.named("check") {
    dependsOn(checkTask)
  }
}

dependencies {
  add("errorprone", libs.findLibrary("errorprone-core").get())
  add("errorprone", libs.findLibrary("nullaway").get())

  add("compileOnly", libs.findLibrary("jspecify").get())
  add("testCompileOnly", libs.findLibrary("jspecify").get())

  add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
  add("testImplementation", libs.findLibrary("junit-jupiter").get())
  add("testImplementation", libs.findLibrary("assertj").get())
  add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}
