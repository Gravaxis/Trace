plugins {
  id("trace.published-api-conventions")
}

description = "Public API for Trace. Apache-2.0 so any plugin can integrate without licence friction."

dependencies {
  // The API may reference Paper types (worlds, players, block data) but never Trace internals.
  compileOnly(libs.paper.api)
}

// This module is Apache-2.0, not GPL. The shared conventions know that and install the matching
// SPDX header; see build-logic/src/main/kotlin/trace.java-conventions.gradle.kts.
