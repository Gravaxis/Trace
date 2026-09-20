package trace.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Gate P1: a benchmark whose contract is "allocates nothing per operation" must measure exactly
 * that.
 *
 * JMH's GC profiler reports `gc.alloc.rate.norm` in bytes per operation. The build spec fixes the
 * threshold at 0.0 B/op, and this task holds it there. An empty baseline benchmark is measured in
 * the same run so that the number has context: if JMH's own measurement floor were not zero, the
 * gate would say so with evidence rather than being quietly loosened.
 */
abstract class AllocationGateTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jmhResults: RegularFileProperty

    /** Fully qualified `class.method` names that must not allocate. */
    @get:Input
    abstract val zeroAllocationBenchmarks: ListProperty<String>

    /**
     * Benchmarks that deliberately allocate. If one of these reads as (near) zero, the profiler is
     * not measuring anything and a pass on the list above would be meaningless.
     */
    @get:Input
    abstract val controlBenchmarks: ListProperty<String>

    /**
     * The largest per-operation figure treated as "no allocation", in bytes.
     *
     * JMH normalises a whole iteration's allocation over its operations, so its own scaffolding
     * leaves a floor well below one byte per operation. The smallest object the JVM can allocate is
     * 16 bytes, so any threshold far below that still fails on a single real allocation per
     * thousands of operations. docs/decisions/0009-allocation-gate.md has the measurements.
     */
    @get:Input
    abstract val thresholdBytesPerOp: Property<Double>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val results = Json.asList(Json.parse(jmhResults.get().asFile.readText()))
        if (results.isEmpty()) {
            throw GradleException("No JMH results in ${jmhResults.get().asFile}")
        }

        val measured = LinkedHashMap<String, Double?>()
        results.forEach { entry ->
            val result = Json.asMap(entry)
            val benchmark = result["benchmark"] as? String ?: return@forEach
            measured[benchmark] = allocationPerOp(result)
        }

        val threshold = thresholdBytesPerOp.get()
        val violations = mutableListOf<String>()
        val missing = mutableListOf<String>()
        zeroAllocationBenchmarks.get().forEach { name ->
            when (val value = measured[name]) {
                null -> if (!measured.containsKey(name)) {
                    missing += name
                } else {
                    violations += "$name reported no gc.alloc.rate.norm — was the gc profiler enabled?"
                }
                else -> if (value > threshold) {
                    violations += "$name allocated %.6f B/op; the limit is %.6f".format(value, threshold)
                }
            }
        }
        // A control that stops registering means the instrument broke, not that the code improved.
        controlBenchmarks.get().forEach { name ->
            val value = measured[name]
            if (value == null) {
                missing += name
            } else if (value < MIN_OBJECT_BYTES) {
                violations += "control $name reported only %.6f B/op; the GC profiler is not measuring allocation"
                    .format(value)
            }
        }

        writeReport(measured, violations, threshold)
        measured.forEach { (name, value) ->
            logger.lifecycle("alloc %-70s %s B/op".format(name, value?.let { "%.6f".format(it) } ?: "n/a"))
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These benchmarks are gated but were not run: ${missing.joinToString(", ")}. " +
                    "Check the include pattern passed to jmhRun."
            )
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Allocation gate failed:\n  " + violations.joinToString("\n  "))
        }
    }

    /** JMH has used both `gc.alloc.rate.norm` and a middle-dot-prefixed variant over the years. */
    private companion object {
        /** The smallest object any HotSpot heap can hand out; a real allocation is never less. */
        const val MIN_OBJECT_BYTES = 16.0
    }

    private fun allocationPerOp(result: Map<String, Any?>): Double? {
        val secondary = Json.asMap(result["secondaryMetrics"])
        val key = secondary.keys.firstOrNull { it.endsWith("gc.alloc.rate.norm") } ?: return null
        return Json.asDouble(Json.asMap(secondary[key])["score"])
    }

    private fun writeReport(measured: Map<String, Double?>, violations: List<String>, threshold: Double) {
        val file = report.get().asFile
        file.parentFile.mkdirs()
        val rows = measured.entries.joinToString(",\n    ") { (name, value) ->
            """{"benchmark": ${Json.quote(name)}, "allocBytesPerOp": ${value ?: "null"}}"""
        }
        file.writeText(
            """
            {
              "gate": "P1 zero allocation",
              "thresholdBytesPerOp": $threshold,
              "measured": [
                $rows
              ],
              "violations": ${violations.size}
            }

            """.trimIndent()
        )
    }
}
