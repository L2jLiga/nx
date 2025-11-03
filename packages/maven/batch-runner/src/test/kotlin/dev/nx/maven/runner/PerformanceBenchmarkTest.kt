package dev.nx.maven.runner

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertTrue

/**
 * Performance benchmark for CachedMavenExecutor to measure overhead.
 */
class PerformanceBenchmarkTest {

    @Test
    fun `benchmark Maven execution overhead`() {
        val executor = CachedMavenExecutor()
        val workingDir = File(System.getProperty("user.dir"))
        System.setProperty("maven.multiModuleProjectDirectory", workingDir.absolutePath)

        try {
            // Warm up (first execution initializes container)
            println("\n=== WARM UP ===")
            val warmupStart = System.nanoTime()
            val warmupOutput = ByteArrayOutputStream()
            val warmupExit = executor.execute(
                goals = listOf("--version"),
                arguments = emptyList(),
                workingDir = workingDir,
                outputStream = warmupOutput
            )
            val warmupDuration = (System.nanoTime() - warmupStart) / 1_000_000.0
            println("Warm-up (first execution): ${warmupDuration}ms")
            println("Warm-up output length: ${warmupOutput.size()} bytes")
            assertTrue(warmupExit == 0, "Warm-up should succeed")

            // Run multiple times to measure steady-state overhead
            println("\n=== STEADY STATE (10 executions) ===")
            val iterations = 10
            val durations = mutableListOf<Double>()

            for (i in 1..iterations) {
                val output = ByteArrayOutputStream()
                val start = System.nanoTime()
                val exitCode = executor.execute(
                    goals = listOf("--version"),
                    arguments = emptyList(),
                    workingDir = workingDir,
                    outputStream = output
                )
                val duration = (System.nanoTime() - start) / 1_000_000.0
                durations.add(duration)

                println("Iteration $i: ${String.format("%.2f", duration)}ms (output: ${output.size()} bytes, exit: $exitCode)")
                assertTrue(exitCode == 0, "Iteration $i should succeed")
            }

            // Calculate statistics
            println("\n=== STATISTICS ===")
            val minDuration = durations.minOrNull() ?: 0.0
            val maxDuration = durations.maxOrNull() ?: 0.0
            val avgDuration = durations.average()
            val medianDuration = durations.sorted()[durations.size / 2]

            println("Min:    ${String.format("%.2f", minDuration)}ms")
            println("Max:    ${String.format("%.2f", maxDuration)}ms")
            println("Avg:    ${String.format("%.2f", avgDuration)}ms")
            println("Median: ${String.format("%.2f", medianDuration)}ms")
            println("Range:  ${String.format("%.2f", maxDuration - minDuration)}ms")

            // Overhead analysis
            println("\n=== OVERHEAD ANALYSIS ===")
            println("Warm-up overhead (includes container init): ${String.format("%.2f", warmupDuration)}ms")
            println("Per-execution overhead (steady state):      ${String.format("%.2f", avgDuration)}ms")
            println("Container initialization cost:             ${String.format("%.2f", warmupDuration - avgDuration)}ms")

            // Performance expectation
            println("\n=== PERFORMANCE SUMMARY ===")
            println("Warm-up (first execution with container init): ${String.format("%.0f", warmupDuration)}ms")
            println("Cached execution (steady state average):       ${String.format("%.0f", avgDuration)}ms")
            println("Improvement factor:                           ${String.format("%.1f", warmupDuration / avgDuration)}x")

        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `benchmark multiple sequential executions`() {
        val executor = CachedMavenExecutor()
        val workingDir = File(System.getProperty("user.dir"))
        System.setProperty("maven.multiModuleProjectDirectory", workingDir.absolutePath)

        try {
            println("\n=== SEQUENTIAL EXECUTION BENCHMARK ===")

            // Run 20 executions and measure cumulative time
            val iterations = 20
            val startTotal = System.nanoTime()

            for (i in 1..iterations) {
                val output = ByteArrayOutputStream()
                executor.execute(
                    goals = listOf("--version"),
                    arguments = emptyList(),
                    workingDir = workingDir,
                    outputStream = output
                )
            }

            val totalDuration = (System.nanoTime() - startTotal) / 1_000_000.0
            val avgPerExecution = totalDuration / iterations

            println("Total time for $iterations executions: ${String.format("%.0f", totalDuration)}ms")
            println("Average per execution:                  ${String.format("%.1f", avgPerExecution)}ms")
            println("Throughput:                             ${String.format("%.0f", 1000.0 / avgPerExecution)} executions/sec")

        } finally {
            executor.shutdown()
        }
    }
}
