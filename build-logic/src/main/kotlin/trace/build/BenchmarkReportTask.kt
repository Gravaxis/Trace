package trace.build

import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Collects a benchmark run into `benchmarks/results/<date>-<commit>/` and regenerates the README's
 * table from it.
 *
 * The table is never written by hand. That is the point of the whole exercise: the project's
 * central claim is that its numbers are reproducible, so the numbers a reader sees have to come
 * from a committed result file, alongside the machine and the server build that produced them.
 *
 * In `verify` mode it regenerates the table in memory and fails if the README has drifted.
 */
@DisableCachingByDefault(because = "Writes a timestamped directory; cheap to re-run")
abstract class BenchmarkReportTask : DefaultTask() {

    private var commit: String = "unknown"
    private var dirty: Boolean = false

    /** `write` or `verify`. */
    @get:Input
    abstract val mode: Property<String>

    /** The repository to ask for the commit and the working-tree state, at execution time. */
    @get:Input
    abstract val repositoryRoot: Property<String>

    @get:Input
    abstract val gradleVersion: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jmhResults: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allocationGateReport: RegularFileProperty

    @get:InputFiles
    abstract val scenarioResults: ConfigurableFileCollection

    @get:InputFiles
    abstract val crashReports: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val serverPins: RegularFileProperty

    @get:Input
    abstract val resultsRoot: Property<String>

    @get:Input
    abstract val readmePath: Property<String>

    @TaskAction
    fun run() {
        val readme = File(readmePath.get())
        val root = File(resultsRoot.get())
        commit = git("rev-parse", "--short=12", "HEAD").ifBlank { "unknown" }
        // Asked at execution time, not configuration time: a value captured in the configuration
        // cache would report the state of some earlier build. Results this task writes are ignored,
        // since they appear while it runs.
        dirty = git("status", "--porcelain")
            .lines()
            .filter { it.isNotBlank() }
            .any { !it.substringAfter(' ').trim().startsWith("benchmarks/results/") }
        when (mode.get()) {
            "write" -> write(root, readme)
            "verify" -> verify(root, readme)
            else -> throw GradleException("mode must be 'write' or 'verify', not '${mode.get()}'")
        }
    }

    private fun write(root: File, readme: File) {
        val results = jsonFilesIn(scenarioResults)
        val crashes = jsonFilesIn(crashReports)
        if (results.isEmpty() && crashes.isEmpty() && !jmhResults.isPresent) {
            throw GradleException("Nothing to report: no scenario results, crash reports or JMH results.")
        }

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val suffix = if (dirty) "-dirty" else ""
        val dir = root.resolve("$date-$commit$suffix")
        dir.mkdirs()

        dir.resolve("environment.json").writeText(environmentJson(now))
        copyInto(dir.resolve("scenarios"), results)
        copyInto(dir.resolve("crash"), crashes)
        if (jmhResults.isPresent && jmhResults.get().asFile.isFile) {
            jmhResults.get().asFile.copyTo(dir.resolve("jmh-result.json"), overwrite = true)
        }
        if (allocationGateReport.isPresent && allocationGateReport.get().asFile.isFile) {
            allocationGateReport.get().asFile.copyTo(dir.resolve("allocation-gate.json"), overwrite = true)
        }

        val table = renderTable(dir)
        readme.writeText(replaceTable(readme.readText(), table))
        logger.lifecycle("Wrote ${dir.relativeTo(root.parentFile.parentFile)} and regenerated the README table")
    }

    private fun verify(root: File, readme: File) {
        val latest = latestResultDirectory(root)
        val expected = if (latest == null) EMPTY_TABLE else renderTable(latest)
        val actual = currentTable(readme.readText())
        if (actual == null) {
            throw GradleException("README.md has no <!-- bench:start --> / <!-- bench:end --> markers.")
        }
        if (actual.trim() != expected.trim()) {
            throw GradleException(
                "The README benchmark table does not match ${latest?.name ?: "the absence of results"}.\n" +
                    "Run ./gradlew benchmarkReport (or benchmark) and commit the result.\n\n" +
                    "Expected:\n$expected\n\nFound:\n$actual"
            )
        }
        logger.lifecycle("README benchmark table matches ${latest?.name ?: "the empty state"}")
    }

    /** The inputs are directories of results; a directory that does not exist yet is simply empty. */
    private fun jsonFilesIn(collection: org.gradle.api.file.FileCollection): List<File> =
        collection.files
            .flatMap { file ->
                when {
                    file.isDirectory -> file.listFiles()?.toList() ?: emptyList()
                    else -> listOf(file)
                }
            }
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.name }

    private fun git(vararg args: String): String = try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(File(repositoryRoot.get()))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() == 0) output.trim() else ""
    } catch (e: Exception) {
        logger.warn("Could not run git ${args.joinToString(" ")}: {}", e.toString())
        ""
    }

    private fun copyInto(target: File, files: List<File>) {
        if (files.isEmpty()) return
        target.mkdirs()
        files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }

    private fun latestResultDirectory(root: File): File? =
        root.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }

    private fun environmentJson(now: ZonedDateTime): String {
        val pins = java.util.Properties().apply {
            serverPins.get().asFile.inputStream().use { load(it) }
        }
        val runtime = Runtime.getRuntime()
        val entries = buildList {
            add("timestamp" to Json.quote(now.format(DateTimeFormatter.ISO_INSTANT)))
            add("commit" to Json.quote(commit))
            add("dirtyWorkingTree" to dirty.toString())
            add("os" to Json.quote("${System.getProperty("os.name")} ${System.getProperty("os.version")}"))
            add("arch" to Json.quote(System.getProperty("os.arch")))
            add("cpu" to Json.quote(cpuModel()))
            add("cpuThreads" to runtime.availableProcessors().toString())
            add("jvm" to Json.quote("${System.getProperty("java.vendor")} ${Runtime.version()}"))
            add("gradle" to Json.quote(gradleVersion.get()))
            add("paperBuild" to Json.quote("${pins.getProperty("paper.version")} build ${pins.getProperty("paper.build")}"))
            add("foliaBuild" to Json.quote("${pins.getProperty("folia.version")} build ${pins.getProperty("folia.build")}"))
        }
        return entries.joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}\n") { "${Json.quote(it.first)}: ${it.second}" }
    }

    private fun cpuModel(): String {
        System.getenv("PROCESSOR_IDENTIFIER")?.let { return it }
        val cpuinfo = File("/proc/cpuinfo")
        if (cpuinfo.isFile) {
            cpuinfo.useLines { lines ->
                lines.firstOrNull { it.startsWith("model name") }?.let { return it.substringAfter(':').trim() }
            }
        }
        return "unknown"
    }

    /** One row per scenario result, with a column per metric that appeared in the run. */
    private fun renderTable(resultDir: File): String {
        val scenarioFiles = resultDir.resolve("scenarios").listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (scenarioFiles.isEmpty()) {
            return EMPTY_TABLE
        }

        data class Row(val scenario: String, val server: String, val params: String, val metrics: Map<String, String>)

        val rows = scenarioFiles.map { file ->
            val result = Json.asMap(Json.parse(file.readText()))
            val details = Json.asMap(result["details"])
            val params = Json.asMap(result["params"])
            val metrics = Json.asList(result["metrics"]).associate { entry ->
                val metric = Json.asMap(entry)
                (metric["name"] as? String ?: "?") to formatMetric(metric)
            }
            Row(
                scenario = result["scenario"] as? String ?: file.nameWithoutExtension,
                server = listOfNotNull(details["server.brand"], details["server.minecraftVersion"])
                    .joinToString(" ")
                    .ifBlank { "?" },
                params = params.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "—" },
                metrics = metrics,
            )
        }
        val metricNames = rows.flatMap { it.metrics.keys }.distinct().sorted()

        val out = StringBuilder()
        out.append("| Scenario | Server | Parameters | ").append(metricNames.joinToString(" | ")).append(" |\n")
        out.append("|---|---|---|").append(metricNames.joinToString("") { "---|" }).append("\n")
        rows.forEach { row ->
            out.append("| `${row.scenario}` | ${row.server} | ${row.params} | ")
            out.append(metricNames.joinToString(" | ") { row.metrics[it] ?: "—" })
            out.append(" |\n")
        }

        val allocationGate = resultDir.resolve("allocation-gate.json")
        if (allocationGate.isFile) {
            val measured = Json.asList(Json.asMap(Json.parse(allocationGate.readText()))["measured"])
            val lines = measured.mapNotNull { entry ->
                val row = Json.asMap(entry)
                val name = row["benchmark"] as? String ?: return@mapNotNull null
                val value = Json.asDouble(row["allocBytesPerOp"]) ?: return@mapNotNull null
                "`${name.substringAfterLast('.')}` ${formatBytesPerOp(value)} B/op"
            }
            if (lines.isNotEmpty()) {
                out.append("\nAllocation gate (JMH `gc.alloc.rate.norm`): ").append(lines.joinToString(", ")).append(".\n")
            }
        }

        crashSummary(resultDir)?.let { out.append("\nCrash injection: ").append(it).append(".\n") }

        out.append("\nMeasured on ").append(environmentSummary(resultDir))
            .append(". Raw results: [`benchmarks/results/").append(resultDir.name).append("`](benchmarks/results/")
            .append(resultDir.name).append(").\n")
        return out.toString()
    }

    /** Small allocation figures matter: 0.000005 and 0 are different claims. */
    private fun formatBytesPerOp(value: Double): String = when {
        value == 0.0 -> "0"
        value < 0.001 -> "%.1e".format(value)
        else -> "%.3f".format(value)
    }

    private fun crashSummary(resultDir: File): String? {
        val files = resultDir.resolve("crash").listFiles { f -> f.extension == "json" } ?: return null
        if (files.isEmpty()) return null
        val parts = files.sortedBy { it.name }.mapNotNull { file ->
            val report = Json.asMap(Json.parse(file.readText()))
            val outcomes = Json.asList(report["outcomes"])
            if (outcomes.isEmpty()) return@mapNotNull null
            val verified = outcomes.count { Json.asMap(it)["verified"] == true }
            val delays = outcomes.mapNotNull { Json.asDouble(Json.asMap(it)["killDelayMs"])?.toLong() }
            val server = report["server"] as? String ?: "?"
            "$verified/${outcomes.size} restarts verified on $server after SIGKILL at ${delays.joinToString(", ")} ms"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun environmentSummary(resultDir: File): String {
        val env = resultDir.resolve("environment.json")
        if (!env.isFile) return "an unrecorded machine"
        val map = Json.asMap(Json.parse(env.readText()))
        return listOfNotNull(
            map["cpu"] as? String,
            (map["cpuThreads"] as? Double)?.let { "${it.toInt()} threads" },
            map["os"] as? String,
            map["jvm"] as? String,
            (map["paperBuild"] as? String)?.let { "Paper $it" },
        ).joinToString(", ")
    }

    private fun formatMetric(metric: Map<String, Any?>): String {
        val value = Json.asDouble(metric["value"]) ?: return "—"
        return when (metric["unit"] as? String) {
            "bytes" -> "%.1f MiB".format(value / (1024 * 1024))
            "ms" -> "%.2f ms".format(value)
            "count" -> value.toLong().toString()
            null -> "%.3f".format(value)
            else -> "%.3f %s".format(value, metric["unit"])
        }
    }

    private fun currentTable(readme: String): String? {
        val start = readme.indexOf(START_MARKER)
        val end = readme.indexOf(END_MARKER)
        if (start < 0 || end < 0) return null
        return readme.substring(start + START_MARKER.length, end)
    }

    private fun replaceTable(readme: String, table: String): String {
        val start = readme.indexOf(START_MARKER)
        val end = readme.indexOf(END_MARKER)
        if (start < 0 || end < 0) {
            throw GradleException("README.md has no <!-- bench:start --> / <!-- bench:end --> markers.")
        }
        return readme.substring(0, start + START_MARKER.length) + "\n" + table.trim() + "\n" + readme.substring(end)
    }

    private companion object {
        const val START_MARKER = "<!-- bench:start -->"
        const val END_MARKER = "<!-- bench:end -->"
        const val EMPTY_TABLE = "_No benchmark results have been recorded yet._"
    }
}
