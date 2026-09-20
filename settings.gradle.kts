pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
      name = "papermc"
      content {
        includeGroup("io.papermc.paper")
        includeGroup("dev.folia")
        includeGroup("io.papermc")
        includeGroup("com.mojang")
        includeGroup("net.md-5")
      }
    }
  }
}

includeBuild("build-logic")

rootProject.name = "trace"

include(
  "trace-api",
  "trace-core",
  "trace-storage-api",
  "trace-storage-sqlite",
  "trace-paper",
  "trace-test-harness",
  "benchmarks",
)
