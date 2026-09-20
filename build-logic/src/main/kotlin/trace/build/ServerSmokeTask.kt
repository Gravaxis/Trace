package trace.build

import java.io.BufferedReader
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Boots a pinned Paper or Folia server with the built plugins, waits for the test harness to report
 * a result, and fails the build if it does not arrive.
 *
 * The server jar is downloaded from PaperMC's content store by its pinned sha256 and verified
 * before it is ever executed. The EULA file is written because the repository owner accepted the
 * Minecraft EULA for these throwaway servers; see docs/decisions/0001-platform-target.md.
 */
abstract class ServerSmokeTask : DefaultTask() {

    /** `paper` or `folia`. */
    @get:Input
    abstract val serverProject: Property<String>

    @get:Input
    abstract val mcVersion: Property<String>

    @get:Input
    abstract val buildNumber: Property<Int>

    @get:Input
    abstract val sha256: Property<String>

    /** Contact placed in the User-Agent; PaperMC's download service requires a non-generic one. */
    @get:Input
    abstract val contact: Property<String>

    @get:Input
    abstract val scenario: Property<String>

    @get:Input
    @get:Optional
    abstract val timeoutSeconds: Property<Int>

    /** Distinct per task, so two scenarios can run in parallel without fighting over a port. */
    @get:Input
    abstract val port: Property<Int>

    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:InputFiles
    abstract val pluginJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    /** Shared download cache, outside the project tree. */
    @get:Internal
    abstract val serverCacheDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val jar = resolveServerJar()
        val dir = runDirectory.get().asFile
        prepareRunDirectory(dir)

        val command = buildList {
            add(javaExecutable.get())
            addAll(jvmArgs.get())
            add("-Dcom.mojang.eula.agree=true")
            add("-Dtrace.harness.scenario=${scenario.get()}")
            add("-jar")
            add(jar.absolutePath)
            add("--nogui")
        }
        logger.lifecycle("Starting ${serverProject.get()} ${mcVersion.get()} build ${buildNumber.get()} for scenario '${scenario.get()}'")

        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        val log = dir.resolve("server.log")
        val tail = ArrayDeque<String>()
        var harnessLine: String? = null
        val pump = Thread {
            process.inputStream.bufferedReader().use { reader: BufferedReader ->
                log.bufferedWriter().use { out ->
                    reader.lineSequence().forEach { line ->
                        out.write(line)
                        out.newLine()
                        out.flush()
                        logger.info(line)
                        if (line.contains(HARNESS_MARKER)) {
                            harnessLine = line
                        }
                        tail.addLast(line)
                        if (tail.size > 60) tail.removeFirst()
                    }
                }
            }
        }
        pump.isDaemon = true
        pump.start()

        val timeout = timeoutSeconds.getOrElse(300).toLong()
        val finished = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
            throw GradleException(
                "Server did not shut down within ${timeout}s for scenario '${scenario.get()}'.\n" +
                    "Last lines:\n" + tail.joinToString("\n")
            )
        }
        pump.join(TimeUnit.SECONDS.toMillis(10))

        val exit = process.exitValue()
        val resultFile = dir.resolve("harness-result.json")
        if (!resultFile.isFile) {
            throw GradleException(
                "Harness wrote no result for scenario '${scenario.get()}' (server exit code $exit).\n" +
                    "Last lines:\n" + tail.joinToString("\n")
            )
        }
        val result = resultFile.readText()
        val passed = Regex("\"status\"\\s*:\\s*\"PASS\"").containsMatchIn(result)
        logger.lifecycle("Harness result: ${harnessLine ?: result.replace("\n", " ")}")
        if (!passed || exit != 0) {
            throw GradleException(
                "Scenario '${scenario.get()}' failed (exit code $exit).\nResult: $result\n" +
                    "Last lines:\n" + tail.joinToString("\n")
            )
        }
    }

    private fun prepareRunDirectory(dir: File) {
        dir.mkdirs()
        dir.resolve("harness-result.json").delete()
        dir.resolve("eula.txt").writeText(
            """
            # The Minecraft EULA (https://aka.ms/MinecraftEULA) was accepted by the repository owner
            # for these throwaway test servers. See docs/decisions/0001-platform-target.md.
            eula=true

            """.trimIndent()
        )
        dir.resolve("server.properties").writeText(
            """
            # Generated by Trace's test harness. Deterministic, offline, minimal work per tick.
            # Bound to loopback on a task-specific port so parallel scenarios never collide.
            online-mode=false
            server-ip=127.0.0.1
            server-port=${port.get()}
            enable-status=false
            enable-query=false
            enable-rcon=false
            level-type=minecraft\:flat
            level-seed=trace-harness
            generate-structures=false
            spawn-protection=0
            spawn-monsters=false
            spawn-npcs=false
            spawn-animals=false
            allow-nether=false
            view-distance=4
            simulation-distance=4
            max-players=5
            enable-command-block=false
            sync-chunk-writes=false
            motd=Trace test server

            """.trimIndent()
        )
        val pluginsDir = dir.resolve("plugins")
        pluginsDir.mkdirs()
        pluginsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.forEach { it.delete() }
        pluginJars.files.forEach { jar ->
            jar.copyTo(pluginsDir.resolve(jar.name), overwrite = true)
        }
    }

    private fun resolveServerJar(): File {
        val name = "${serverProject.get()}-${mcVersion.get()}-${buildNumber.get()}.jar"
        val cache = serverCacheDirectory.get().asFile
        cache.mkdirs()
        val target = cache.resolve(name)
        val expected = sha256.get().lowercase()
        if (target.isFile && sha256Of(target) == expected) {
            return target
        }
        val url = "https://fill-data.papermc.io/v1/objects/$expected/$name"
        logger.lifecycle("Downloading $name from PaperMC")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Trace-build/0.1 (+https://gravaxis.in; ${contact.get()})")
            .GET()
            .build()
        val tmp = File(cache, "$name.tmp")
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        if (response.statusCode() != 200) {
            tmp.delete()
            throw GradleException("Download of $url failed with HTTP ${response.statusCode()}")
        }
        val actual = sha256Of(tmp)
        if (actual != expected) {
            tmp.delete()
            throw GradleException("Checksum mismatch for $name: expected $expected, got $actual")
        }
        tmp.renameTo(target)
        return target
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val HARNESS_MARKER = "[TRACE-HARNESS]"
    }
}
