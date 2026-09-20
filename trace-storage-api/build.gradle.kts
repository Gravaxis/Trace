plugins {
  id("trace.java-conventions")
  // The contract suite below is the storage spec: every backend subclasses it and must pass it.
  `java-test-fixtures`
}

description =
  "The storage SPI: scan plans, streaming cursors, the shard manifest and the writer contract. " +
    "Every backend implements this and is validated by the same shared test suite."

dependencies {
  api(project(":trace-core"))

  testFixturesApi(platform(libs.junit.bom))
  testFixturesApi(libs.junit.jupiter)
  testFixturesApi(libs.assertj)
  testFixturesApi(testFixtures(project(":trace-core")))
  testFixturesCompileOnly(libs.jspecify)
}
