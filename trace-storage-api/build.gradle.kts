plugins {
  id("trace.java-conventions")
}

description =
  "The storage SPI: scan plans, streaming cursors, the shard manifest and the writer contract. " +
    "Every backend implements this and is validated by the same shared test suite."

dependencies {
  api(project(":trace-core"))
}
