package trace.build

import java.io.File
import java.util.SplittableRandom
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
 * Kills a running server without warning and checks what survived.
 *
 * Each iteration runs a writing scenario, waits for it to report that it is under way, kills the
 * JVM with `destroyForcibly` after a seeded random delay, restarts into the *same* directory and
 * runs a verifying scenario. The seed is an input, so a failure is reproducible: the delay that
 * produced it is printed and recorded.
 *
 * This exists in M1, before there is anything of Trace's to lose, because the durability gates
 * (P9, P10) are only as trustworthy as the rig that tests them — and a rig written after the
 * feature tends to test what the feature happens to do.
 */
abstract class CrashInjectionTask : DefaultTask() {

    @get:Input
    abstract val serverProject: Property<String>

    @get:Input
    abstract val mcVersion: Property<String>

    @get:Input
    abstract val buildNumber: Property<Int>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val contact: Property<String>

    /** Scenario that writes and reports readiness. */
    @get:Input
    abstract val writeScenario: Property<String>

    /** Scenario that checks, after the restart, what survived. */
    @get:Input
    abstract val verifyScenario: Property<String>

    @get:Input
    abstract val scenarioParams: MapProperty<String, String>

    @get:Input
    abstract val port: Property<Int>

    @get:Input
    abstract val iterations: Property<Int>

    /** Kill delay range, measured from the moment the writer reports it is running. */
    @get:Input
    abstract val minKillDelayMillis: Property<Long>

    @get:Input
    abstract val maxKillDelayMillis: Property<Long>

    /** Fixed so a failing run can be replayed exactly. */
    @get:Input
    abstract val seed: Property<Long>

    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val timeoutSeconds: Property<Int>

    @get:InputFiles
    abstract val pluginJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val runDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    /**
     * Iterations that must have lost at least one event for the run to count.
     *
     * A kill that lands between ticks loses nothing, and a verification with nothing to verify
     * passes without testing anything. Zero leaves the older crash task alone; the journal tasks set
     * it to one, so a set of iterations that never once exercised the gap-coverage property is a
     * failure and not a quiet success.
     */
    @get:Input
    @get:Optional
    abstract val minLossyIterations: Property<Int>

    @get:Internal
    abstract val serverCacheDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val spec = ServerSpec(serverProject.get(), mcVersion.get(), buildNumber.get(), sha256.get())
        val jar = ServerRuntime.resolveJar(spec, serverCacheDirectory.get().asFile, contact.get(), logger)
        val random = SplittableRandom(seed.get())
        val minDelay = minKillDelayMillis.get()
        val maxDelay = maxKillDelayMillis.get()
        val timeout = timeoutSeconds.getOrElse(420).toLong()
        val outcomes = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var lossyIterations = 0

        repeat(iterations.get()) { iteration ->
            val dir = runDirectory.get().asFile.resolve("iteration-$iteration")
            val delay = minDelay + random.nextLong(maxOf(1L, maxDelay - minDelay + 1))
            ServerRuntime.prepareRunDirectory(dir, port.get(), pluginJars.files, keepWorld = false)

            val writeRun = ServerRuntime.run(
                command = ServerRuntime.command(
                    javaExecutable.get(), jvmArgs.get(), jar, writeScenario.get(), scenarioParams.get()
                ),
                directory = dir,
                logFile = dir.resolve("server-write.log"),
                logger = logger,
                timeoutSeconds = timeout,
                killAfterMarker = "$HARNESS_MARKER ready",
                killDelayMillis = delay,
            )
            if (!writeRun.killed) {
                failures += "iteration $iteration: the writer was never killed (${writeRun.describe()})"
                return@repeat
            }

            // Restart into the same directory: whatever the killed JVM left behind is the input.
            ServerRuntime.prepareRunDirectory(dir, port.get(), pluginJars.files, keepWorld = true)
            val verifyRun = ServerRuntime.run(
                command = ServerRuntime.command(
                    javaExecutable.get(), jvmArgs.get(), jar, verifyScenario.get(), scenarioParams.get()
                ),
                directory = dir,
                logFile = dir.resolve("server-verify.log"),
                logger = logger,
                timeoutSeconds = timeout,
            )
            val (passed, text) = ServerRuntime.readResult(dir.resolve("harness-result.json"))
            val resultFile = dir.resolve("harness-result.json")
            val lossy = ServerRuntime.readDetail(resultFile, "crash.lossy") == "true"
            val inconclusive = ServerRuntime.readDetail(resultFile, "crash.inconclusive")
            if (lossy) {
                lossyIterations++
            }
            outcomes += """{"iteration": $iteration, "killDelayMs": $delay, "verified": $passed, "lossy": $lossy}"""
            if (!passed || verifyRun.exitCode != 0) {
                failures += "iteration $iteration (killed after ${delay}ms): $text\n${verifyRun.describe()}"
            } else if (inconclusive != null) {
                logger.lifecycle("Iteration $iteration: killed after ${delay}ms, inconclusive - $inconclusive")
            } else {
                logger.lifecycle("Iteration $iteration: killed after ${delay}ms, restart verified, lost events covered")
            }
        }

        val required = minLossyIterations.getOrElse(0)
        if (failures.isEmpty() && lossyIterations < required) {
            // Everything passed, and that is the problem: nothing was ever lost, so the property
            // this task exists to check was never once evaluated.
            failures += "no iteration lost an event ($lossyIterations of $required required), so the gap-coverage" +
                " property was never exercised. Every kill landed between ticks. This is not a pass; re-run, and" +
                " if it persists the writing scenario is no longer staging work when the kill arrives."
        }

        writeReport(outcomes, failures, lossyIterations)
        if (failures.isNotEmpty()) {
            throw GradleException("Crash injection failed:\n" + failures.joinToString("\n\n"))
        }
    }

    private fun writeReport(outcomes: List<String>, failures: List<String>, lossyIterations: Int) {
        val file: File = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "task": "${name}",
              "server": "${serverProject.get()} ${mcVersion.get()} build ${buildNumber.get()}",
              "seed": ${seed.get()},
              "iterations": ${iterations.get()},
              "killDelayRangeMs": [${minKillDelayMillis.get()}, ${maxKillDelayMillis.get()}],
              "outcomes": [${outcomes.joinToString(", ")}],
              "lossyIterations": $lossyIterations,
              "minLossyIterations": ${minLossyIterations.getOrElse(0)},
              "failures": ${failures.size}
            }

            """.trimIndent()
        )
    }
}
