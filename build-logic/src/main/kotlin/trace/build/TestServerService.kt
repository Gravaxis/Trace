package trace.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serialises the tasks that boot a real server.
 *
 * Each test server takes a gigabyte of heap and a few hundred megabytes of native memory, and the
 * build already runs a Gradle daemon, a Kotlin daemon, worker daemons and JMH forks. Running two of
 * them beside all that is how a developer machine ends up killing a JVM mid-benchmark, which also
 * makes the measurement meaningless. Holding this service to one user at a time keeps a benchmark
 * measuring Trace rather than measuring memory pressure.
 */
abstract class TestServerService : BuildService<BuildServiceParameters.None>
