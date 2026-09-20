package trace.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Enforces the module dependency rules from the build spec: `trace-api` never sees `trace-core`,
 * and `trace-core` never sees a server class. The rules are what keep the API independently
 * licensable and the engine unit-testable without booting a server, so they are checked
 * mechanically rather than by review.
 */
abstract class ModuleGraphCheckTask : DefaultTask() {

    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val allowedProjects: SetProperty<String>

    @get:Input
    abstract val actualProjects: SetProperty<String>

    @TaskAction
    fun check() {
        val allowed = allowedProjects.get()
        val actual = actualProjects.get()
        val forbidden = (actual - allowed).sorted()
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "${modulePath.get()} must not depend on ${forbidden.joinToString(", ")} " +
                    "(allowed: ${allowed.sorted().joinToString(", ").ifEmpty { "nothing" }}). " +
                    "See docs/decisions/0006-licence-and-module-layout.md."
            )
        }
        logger.info("{}: module graph ok ({})", modulePath.get(), actual.sorted())
    }
}
