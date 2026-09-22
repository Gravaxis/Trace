plugins {
  id("trace.java-conventions")
  // The property harness and the fake stores are shared with the storage modules' suites.
  `java-test-fixtures`
}

description =
  "Domain model, query planner, codecs and the rollback engine. Deliberately free of any server " +
    "dependency so it can be unit- and property-tested without booting Minecraft."

dependencies {
  api(project(":trace-api"))

  testFixturesCompileOnly(libs.jspecify)
  testImplementation(testFixtures(project(":trace-core")))
  testImplementation(libs.archunit)
}

tasks.test {
  systemProperty("trace.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.register<Test>("payloadQueueTest") {
  group = "verification"
  description = "Fresh bounded-payload branch and process-kill proof, without a server."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  systemProperty("trace.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
  useJUnitPlatform()
  filter { includeTestsMatching("*BoundedPayloadQueueTest") }
  outputs.upToDateWhen { false }
}

tasks.register<Test>("payloadJournalTest") {
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*CaptureEnvelopeTest") }
  outputs.upToDateWhen { false }
}

tasks.register<Test>("confirmationTest") {
  group = "verification"
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform()
  filter { includeTestsMatching("*CaptureServiceTest") }
  outputs.upToDateWhen { false }
}
