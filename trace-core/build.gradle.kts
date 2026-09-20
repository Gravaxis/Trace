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
