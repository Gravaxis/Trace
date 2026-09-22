import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("trace.paper-plugin-conventions")
  id("trace.dev-server-conventions")
}

description = "The Trace plugin: listeners, commands, scheduling, configuration and the bootstrap."

tasks.test {
  systemProperty("trace.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.register<Test>("dictionaryTest") {
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  systemProperty("trace.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
  useJUnitPlatform()
  filter { includeTestsMatching("*DictionaryPublicationTest") }
  outputs.upToDateWhen { false }
}

tasks.register<Copy>("generateConfigReference") {
  group = "documentation"
  description = "Generates the commented configuration reference from the packaged template."
  from(layout.projectDirectory.file("src/main/resources/config.yml"))
  into(rootProject.layout.projectDirectory.dir("docs/generated"))
}

tasks.register<Test>("payloadConsumerTest") {
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*PayloadConsumerTest") }
  outputs.upToDateWhen { false }
}

tasks.register<Test>("confirmationTest") {
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*ConfirmationConsumerTest") }
  outputs.upToDateWhen { false }
}

dependencies {
  implementation(project(":trace-api"))
  implementation(project(":trace-core"))
  implementation(project(":trace-storage-api"))
  implementation(project(":trace-storage-sqlite"))
  compileOnly(libs.snakeyaml)
  testImplementation(libs.snakeyaml)
  testImplementation(libs.sqlite.jdbc)
  testImplementation(libs.slf4j.api)
  testRuntimeOnly(libs.paper.api)
  testImplementation(testFixtures(project(":trace-storage-api")))
}

paperPluginYaml {
  name = "Trace"
  main = "in.gravaxis.trace.Trace"
  // Quoted on purpose: Paper parses api-version as a string, and an unquoted 26.10 would become
  // the float 26.1. The value is the newest STABLE Paper line (see ADR-0001), not the newest
  // Minecraft release.
  apiVersion = "26.2"
  foliaSupported = true
  description = "Block, container and entity logging with rollback."
  authors.add("Gravaxis")
  website = "https://gravaxis.in"
}

// The test harness consumes the shaded jar through this configuration rather than reaching into
// another project's tasks.
val pluginJar = configurations.consumable("pluginJar")
artifacts {
  add(pluginJar.name, tasks.named<ShadowJar>("shadowJar"))
}
