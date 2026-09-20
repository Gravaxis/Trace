plugins {
  id("trace.java-conventions")
}

description =
  "Domain model, query planner, codecs and the rollback engine. Deliberately free of any server " +
    "dependency so it can be unit- and property-tested without booting Minecraft."

dependencies {
  api(project(":trace-api"))

  testImplementation(libs.archunit)
}
