plugins {
  id("trace.java-conventions")
}

description = "Tier S: the default embedded backend, time-sharded SQLite clustered by chunk."

tasks.test {
  systemProperty("trace.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

dependencies {
  api(project(":trace-storage-api"))

  // Paper puts org.xerial:sqlite-jdbc on the server classpath and plugin classloaders delegate to
  // the parent first, so the server's copy always wins at runtime. Compile and test against exactly
  // that version rather than the newest release, or calls added later fail with NoSuchMethodError.
  // See docs/decisions/0003-sqlite-driver.md.
  compileOnly(libs.sqlite.jdbc)

  testImplementation(libs.sqlite.jdbc)
  testImplementation(testFixtures(project(":trace-storage-api")))
  testImplementation(testFixtures(project(":trace-core")))
}
