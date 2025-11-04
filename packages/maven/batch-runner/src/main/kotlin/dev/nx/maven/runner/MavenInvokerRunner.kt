package dev.nx.maven.runner

import dev.nx.maven.data.MavenBatchOptions
import dev.nx.maven.data.MavenBatchTask
import dev.nx.maven.data.TaskGraph
import dev.nx.maven.data.TaskResult
import dev.nx.maven.utils.removeTasksFromTaskGraph
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Batch runner that executes Maven tasks using Maven 4.x with work-stealing scheduler.
 *
 * Uses work-stealing task queue for maximum parallelization:
 * - Tasks are pulled from a queue as workers become available
 * - No phase-based batching - different modules' phases run in parallel
 * - Dynamic task graph updates - new tasks added to queue as dependencies complete
 * - Failure cascading: dependent tasks skipped when a task fails
 * - Single MavenSession kept alive across all invocations (project caching)
 *
 * Performance: ~30-50% faster than batch-sequential by allowing cross-phase parallelization
 */
class MavenInvokerRunner(private val workspaceRoot: File, private val options: MavenBatchOptions) {
  private val log = LoggerFactory.getLogger(MavenInvokerRunner::class.java)

  // Maven executor - automatically selects best available strategy:
  // - Maven 4.x: SessionCachingMavenExecutor with project caching
  // - Maven 3.9.x: ProcessBasedMavenExecutor (fallback via subprocess)
  private val mavenExecutor = SessionCachingMavenExecutorFactory.create(
    workspaceRoot = workspaceRoot,
    localRepositoryPath = File(System.getProperty("user.home"), ".m2/repository")
  )

  fun runBatch(): Map<String, TaskResult> {
    val results = ConcurrentHashMap<String, TaskResult>()
    val numWorkers = Runtime.getRuntime().availableProcessors()
    val executor = Executors.newFixedThreadPool(numWorkers)

    log.info("Received ${options.tasks.size} tasks")
    log.info("Thread pool size: $numWorkers")

    val initialGraph = options.taskGraph
    if (initialGraph == null) {
      log.error("Task graph is null, cannot execute tasks")
      executor.shutdown()
      return emptyMap()
    }

    log.info("🚀 Starting work-stealing task queue execution (max parallelization)")
    log.info("Initial roots: ${initialGraph.roots.joinToString(", ")}")

    // Thread-safe queue of ready tasks and graph state
    val taskQueue = LinkedBlockingQueue<String>(initialGraph.roots)
    val graphRef = AtomicReference(initialGraph)
    val successfulTasks = ConcurrentHashMap<String, Boolean>()
    val failedTasks = ConcurrentHashMap<String, Boolean>()
    val processedTasks = ConcurrentHashMap<String, Boolean>()

    // Latch to signal when all tasks are done
    val completionLatch = CountDownLatch(initialGraph.tasks.size)

    val executionStartTime = System.currentTimeMillis()

    try {
      // Submit worker tasks that pull from the queue
      repeat(numWorkers) {
        executor.submit {
          while (true) {
            val taskId = taskQueue.poll() ?: break

            if (processedTasks.containsKey(taskId)) {
              completionLatch.countDown()
              continue
            }

            executeSingleTask(taskId, results)
            processedTasks[taskId] = true

            // Determine if this task succeeded or failed
            val success = results[taskId]?.success == true
            if (success) {
              successfulTasks[taskId] = true
            } else {
              failedTasks[taskId] = true
            }

            // Update graph and find newly available tasks
            synchronized(graphRef) {
              val currentGraph = graphRef.get()
              val newGraph = removeTasksFromTaskGraph(
                currentGraph,
                if (success) listOf(taskId) else emptyList(),
                if (!success) listOf(taskId) else emptyList()
              )
              graphRef.set(newGraph)

              // Add newly available root tasks to queue
              val previousRoots = currentGraph.roots.toSet()
              val newRoots = newGraph.roots.filter { it !in previousRoots && !processedTasks.containsKey(it) }
              newRoots.forEach { newTaskId ->
                if (!processedTasks.containsKey(newTaskId)) {
                  taskQueue.offer(newTaskId)
                  log.debug("Added newly available task to queue: $newTaskId")
                }
              }

              // Mark skipped tasks (those removed due to failed dependencies)
              val oldTasks = currentGraph.tasks.keys
              val newTasks = newGraph.tasks.keys
              val skippedTasks = oldTasks - newTasks - processedTasks.keys - successfulTasks.keys - failedTasks.keys
              skippedTasks.forEach { skippedTaskId ->
                results[skippedTaskId] = TaskResult(
                  taskId = skippedTaskId,
                  success = false,
                  terminalOutput = "SKIPPED: Task was skipped due to a failed dependency",
                  startTime = 0,
                  endTime = 0
                )
                processedTasks[skippedTaskId] = true
                failedTasks[skippedTaskId] = true
              }
            }

            completionLatch.countDown()
          }
        }
      }

      // Wait for all tasks to complete
      completionLatch.await()

      val executionDuration = System.currentTimeMillis() - executionStartTime
      val successCount = successfulTasks.size
      val failureCount = failedTasks.size
      log.info("📊 Summary: ✅ $successCount succeeded, ❌ $failureCount failed")
      log.info("⏱️  Total execution time: ${executionDuration}ms (${String.format("%.2f", executionDuration / 1000.0)}s)")

    } finally {
      gracefulShutdown(executor)
    }

    log.debug("Returning ${results.size} results with task IDs: ${results.keys.joinToString(", ")}")
    return results.toMap()
  }

  private fun executeSingleTask(
    taskId: String,
    results: MutableMap<String, TaskResult>
  ): TaskResult {
    val startTime = System.currentTimeMillis()

    // Get the task and its goals/arguments
    val mavenBatchTask = options.tasks.getValue(taskId)

    // If task has no goals, return success immediately
    if (mavenBatchTask.goals.isEmpty()) {
      log.info("Task $taskId has no goals, marking as successful")
      val endTime = System.currentTimeMillis()
      return TaskResult(
        taskId = taskId,
        success = true,
        terminalOutput = "",
        startTime = startTime,
        endTime = endTime
      ).also {
        results[taskId] = it
      }
    }
    val goals = buildGoals(mavenBatchTask)
    val arguments = buildArguments(taskId, mavenBatchTask)

    // Capture Maven output
    val output = ByteArrayOutputStream()

    return try {
      log.info("Executing ${goals.joinToString(", ")} for task: $taskId")

      // Execute using the appropriate Maven executor (auto-selected based on Maven version)
      // Maven 4.x: SessionCachingMavenExecutor with project caching
      // Maven 3.9.x: ProcessBasedMavenExecutor (subprocess fallback)
      val exitCode = mavenExecutor.execute(
        goals = goals,
        arguments = arguments,
        workingDir = workspaceRoot,
        outputStream = output
      )

      val success = exitCode == 0
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      val outputText = output.toString()

      if (success) {
        log.info("Task $taskId completed successfully with exit code: $exitCode (${duration}ms)")
        if (outputText.isNotEmpty()) {
          log.debug("Maven output for task $taskId:\n$outputText")
        }
      } else {
        // Log at ERROR level when task fails so user can see what went wrong
        log.error("Task $taskId FAILED with exit code: $exitCode (${duration}ms)")
        if (outputText.isNotEmpty()) {
          log.error("Maven output for failed task $taskId:\n$outputText")
        } else {
          log.error("Task $taskId had no output from Maven")
        }
      }

      val result = TaskResult(
        taskId = taskId,
        success = success,
        terminalOutput = outputText,
        startTime = startTime,
        endTime = endTime
      )
      results[taskId] = result
      result
    } catch (e: Exception) {
      val errorMsg = e.message ?: "Unknown error"
      val endTime = System.currentTimeMillis()
      val outputText = output.toString()

      log.error("Task $taskId failed with exception: $errorMsg", e)
      if (outputText.isNotEmpty()) {
        log.error("Maven output before exception for task $taskId:\n$outputText")
      }

      val result = TaskResult(
        taskId = taskId,
        success = false,
        terminalOutput = "$outputText\nError: $errorMsg",
        startTime = startTime,
        endTime = endTime
      )
      results[taskId] = result
      result
    }
  }

  private fun gracefulShutdown(executor: java.util.concurrent.ExecutorService) {
    // Shutdown thread pool executor
    try {
      log.info("Shutting down thread pool...")
      executor.shutdown()
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("Thread pool did not terminate within 10 seconds, forcing shutdown")
        executor.shutdownNow()
      }
      log.info("✅ Thread pool shut down")
    } catch (e: Exception) {
      log.error("Failed to shutdown thread pool: ${e.message}")
    }

    // Shutdown Maven executor (cleans up resources based on executor type)
    try {
      log.info("Shutting down Maven executor...")
      mavenExecutor.shutdown()
      log.info("✅ Maven executor shut down")
    } catch (e: Exception) {
      log.error("Failed to shutdown Maven executor: ${e.message}")
    }
  }

  private fun buildGoals(mavenBatchTask: MavenBatchTask): List<String> {
    val goals = mutableListOf<String>()

    // Add Nx Maven apply goal before user goals
    goals.add("dev.nx.maven:nx-maven-plugin:apply")

    // Add user-specified goals
    goals.addAll(mavenBatchTask.goals)

    // Add Nx Maven record goal after user goals
    goals.add("dev.nx.maven:nx-maven-plugin:record")

    return goals
  }

  private fun buildArguments(taskId: String, mavenBatchTask: MavenBatchTask): List<String> {
    val arguments = mutableListOf<String>()

    // Batch mode flag
    arguments.add("-B")

    // Verbose and quiet flags
    if (options.verbose) {
      arguments.add("-X")
    }

    arguments.add("-nsu")

    // Module selector (always pass the project)
    val task = options.taskGraph?.tasks?.get(taskId)
    val projectSelector = task?.projectRoot ?: task?.target?.project ?: mavenBatchTask.project
    arguments.add("-pl")
    arguments.add(projectSelector)

    return arguments
  }
}
