import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
  id("trace.java-conventions")
  id("com.gradleup.shadow")
  id("xyz.jpenilla.resource-factory-paper-convention")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  // Paper (and Folia) provide the API, Adventure, Guava, Gson, fastutil, SnakeYAML and the SQLite
  // driver at runtime. Never shade any of them: plugin classloaders are parent-first, so a shaded
  // copy is either ignored or a conflict.
  add("compileOnly", libs.findLibrary("paper-api").get())
  add("testCompileOnly", libs.findLibrary("paper-api").get())
}

tasks.named<Jar>("jar") {
  archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
  archiveClassifier.set("")
  mergeServiceFiles()
  // Everything bundled is relocated. bStats refuses to run unrelocated; RoaringBitmap and friends
  // would otherwise collide with another plugin that shaded the same library.
  relocationPrefix = "in.gravaxis.trace.libs"
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
}

tasks.named("build") {
  dependsOn(tasks.named("shadowJar"))
}

// Folia's newest build is cut from an older Paper commit than the paper-api we compile against, so
// a method added to Paper in between would only fail at runtime, on the platform we claim to
// support natively. Compiling the same sources against folia-api turns that into a build error.
val foliaApi = configurations.dependencyScope("foliaApi")
val foliaApiClasspath = configurations.resolvable("foliaApiClasspath") {
  extendsFrom(foliaApi.get())
}

dependencies {
  add(foliaApi.name, libs.findLibrary("folia-api").get())
}

val javaToolchainService = extensions.getByType<JavaToolchainService>()
val javaVersionForFolia = libs.findVersion("java").get().requiredVersion.toInt()
val mainJava = extensions.getByType<SourceSetContainer>().named("main").map { it.java }
val withoutPaperApi = configurations.named("compileClasspath").map { configuration ->
  configuration.filter { !it.name.startsWith("paper-api") }
}

val compileAgainstFoliaApi = tasks.register<JavaCompile>("compileAgainstFoliaApi") {
  group = "verification"
  description = "Recompiles this plugin against folia-api to catch API that Folia's build lacks."
  source(mainJava)
  classpath = files(foliaApiClasspath, withoutPaperApi)
  destinationDirectory.set(layout.buildDirectory.dir("classes/java/folia-api-check"))
  options.release.set(javaVersionForFolia)
  options.encoding = "UTF-8"
  // A second full Error Prone pass over the same sources buys nothing.
  options.errorprone.enabled.set(false)
  javaCompiler.set(
    javaToolchainService.compilerFor {
      languageVersion.set(JavaLanguageVersion.of(javaVersionForFolia))
    }
  )
}

tasks.named("check") {
  dependsOn(compileAgainstFoliaApi)
}
