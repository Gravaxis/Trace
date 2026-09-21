package trace.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Boots a pinned Paper or Folia server with the built plugins, runs one harness scenario, and fails
 * the build if the scenario does not report a pass.
 *
 * The same task type runs the M0 boot check and the benchmark scenarios, because a benchmark that
 * runs on a differently-configured server than the correctness tests is not measuring the same
 * thing.
 */
abstract class ServerScenarioTask : DefaultTask() {

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

    /** Passed to the harness as `-Dtrace.harness.params=k=v,...`. */
    @get:Input
    abstract val scenarioParams: MapProperty<String, String>

    /** Operator configuration installed before plugin enable, so scheduling tests use real startup. */
    @get:Input
    @get:Optional
    abstract val traceConfig: Property<String>

    /** Distinct per task, so scenarios can run in parallel without fighting over a port. */
    @get:Input
    abstract val port: Property<Int>

    @get:Input
    @get:Optional
    abstract val timeoutSeconds: Property<Int>

    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:InputFiles
    abstract val pluginJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    /** Where the scenario's result is copied so the benchmark report can collect it. */
    @get:OutputFile
    @get:Optional
    abstract val resultCopy: RegularFileProperty

    /** Shared download cache, outside the project tree. */
    @get:Internal
    abstract val serverCacheDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val spec = ServerSpec(serverProject.get(), mcVersion.get(), buildNumber.get(), sha256.get())
        val jar = ServerRuntime.resolveJar(spec, serverCacheDirectory.get().asFile, contact.get(), logger)
        val dir = runDirectory.get().asFile
        ServerRuntime.prepareRunDirectory(dir, port.get(), pluginJars.files, keepWorld = false)
        if (traceConfig.isPresent) {
            val config = dir.resolve("plugins/Trace/config.yml")
            config.parentFile.mkdirs()
            config.writeText(traceConfig.get())
        }

        val run = ServerRuntime.run(
            command = ServerRuntime.command(
                javaExecutable.get(),
                jvmArgs.get(),
                jar,
                scenario.get(),
                scenarioParams.get(),
            ),
            directory = dir,
            logFile = dir.resolve("server.log"),
            logger = logger,
            timeoutSeconds = timeoutSeconds.getOrElse(420).toLong(),
        )
        if (run.timedOut) {
            throw GradleException(
                "Server did not shut down in time for scenario '${scenario.get()}'.\n${run.describe()}"
            )
        }

        val resultFile = dir.resolve("harness-result.json")
        val (passed, text) = ServerRuntime.readResult(resultFile)
        copyResult(resultFile)
        if (!passed || run.exitCode != 0) {
            throw GradleException("Scenario '${scenario.get()}' failed.\nResult: $text\n${run.describe()}")
        }
    }

    private fun copyResult(resultFile: File) {
        if (!resultCopy.isPresent || !resultFile.isFile) {
            return
        }
        val target = resultCopy.get().asFile
        target.parentFile.mkdirs()
        resultFile.copyTo(target, overwrite = true)
    }
}
