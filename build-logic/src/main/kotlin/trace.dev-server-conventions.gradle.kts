import java.util.Properties
import xyz.jpenilla.runpaper.task.RunServer

plugins {
  id("xyz.jpenilla.run-paper")
}

// Pinned server builds live in gradle/servers.properties so Renovate and humans can see them.
val pins = providers
  .fileContents(rootProject.layout.projectDirectory.file("gradle/servers.properties"))
  .asText
  .map { text -> Properties().apply { load(text.reader()) } }

fun pin(key: String) = pins.map { it.getProperty(key) ?: error("missing pin: $key") }

// A development server accepts the Minecraft EULA only because the operator configures it to.
val prepareRunDirectory = tasks.register("prepareRunDirectory") {
  val eula = layout.projectDirectory.file("run/eula.txt")
  outputs.file(eula)
  doLast {
    val file = eula.asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
      # The Minecraft EULA (https://aka.ms/MinecraftEULA) was accepted by the repository owner for
      # local development servers. Delete this file if that is not true for you.
      eula=true

      """.trimIndent()
    )
  }
}

runPaper.folia.registerTask()

tasks.withType<RunServer>().configureEach {
  dependsOn(prepareRunDirectory)
  jvmArgs("-Xms1G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}

tasks.named<RunServer>("runServer") {
  minecraftVersion(pin("paper.version").get())
  build(pin("paper.build").get().toInt())
}

tasks.named<RunServer>("runFolia") {
  minecraftVersion(pin("folia.version").get())
  build(pin("folia.build").get().toInt())
}
