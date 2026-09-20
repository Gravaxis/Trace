plugins {
  base
}

// Module builds carry their own configuration through the convention plugins in build-logic.
// Nothing is configured across projects from here: that keeps the build compatible with Gradle's
// configuration cache today and with Isolated Projects later.

allprojects {
  group = rootProject.group
  version = rootProject.version
}
