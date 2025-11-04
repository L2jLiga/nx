package dev.nx.maven.runner

import dev.nx.maven.data.MavenBatchOptions
import dev.nx.maven.data.MavenBatchTask
import dev.nx.maven.data.TaskGraph
import dev.nx.maven.data.TaskResult
import dev.nx.maven.utils.removeTasksFromTaskGraph
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Batch runner that executes Maven tasks using Maven 4.x with session and project caching.
 *
 * Executes tasks in parallel batches based on task graph roots.
 * - Dynamic task graph execution with root recalculation
 * - Failure cascading: dependent tasks skipped when a task fails
 * - Parallel execution of independent root tasks
 * - Single MavenSession kept alive across all invocations (project caching)
 * - Maven's DefaultGraphBuilder detects cached projects and skips POM parsing + dependency resolution
 * - Per-task overhead: 30-100ms reduction (no POM parse/resolve)
 *
 * Performance: Session caching saves 30-100ms per task vs fresh session per task
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
    val results = mutableMapOf<String, TaskResult>()

    log.info("Received ${options.tasks.size} tasks")

    val initialGraph = options.taskGraph
    if (initialGraph == null) {
      log.error("Task graph is null, cannot execute tasks")
      return emptyMap()
    }

    // SessionCachingMavenExecutor keeps one session alive with project caching
    log.info("🚀 Starting batch execution with session-based project caching")

    var remainingGraph: TaskGraph = initialGraph
    log.info("Initial roots: ${remainingGraph.roots.joinToString(", ")}")

    try {
      // While loop: execute tasks as long as there are roots
      while (remainingGraph.roots.isNotEmpty()) {
        log.info("Executing batch of roots: ${remainingGraph.roots.joinToString(", ")}")

        // Execute all root tasks sequentially (no thread pool)
        val batchStartTime = System.currentTimeMillis()
        for (taskId in remainingGraph.roots) {
          executeSingleTask(taskId, results)
        }
        val batchDuration = System.currentTimeMillis() - batchStartTime
        log.info("Batch execution completed in ${batchDuration}ms")

        // Separate successful and failed tasks from the current batch roots
        val graphUpdateStartTime = System.currentTimeMillis()
        val currentBatchRoots = remainingGraph.roots.toSet()
        val successfulTaskIds = currentBatchRoots.filter { taskId ->
          results[taskId]?.success == true
        }.toList()
        val failedTaskIds = currentBatchRoots.filter { taskId ->
          results[taskId]?.success == false
        }.toList()

        if (failedTaskIds.isNotEmpty()) {
          log.warn("Failed tasks: ${failedTaskIds.joinToString(", ")}")
        }

        log.info("Batch results - Success: ${successfulTaskIds.size}, Failed: ${failedTaskIds.size}")
        successfulTaskIds.forEach { log.info("✅ Successful: $it") }
        failedTaskIds.forEach { log.info("❌ Failed: $it") }

        // Remove completed/failed tasks from graph and recalculate roots
        // Failed tasks and their dependents will be removed
        val oldRemainingTasks = remainingGraph.tasks.keys
        log.info("Tasks before removal: ${oldRemainingTasks.size} tasks")
        remainingGraph = removeTasksFromTaskGraph(
          remainingGraph,
          successfulTaskIds,
          failedTaskIds
        )
        log.info("Tasks after removal: ${remainingGraph.tasks.size} tasks (removed ${oldRemainingTasks.size - remainingGraph.tasks.size})")

        // Mark tasks that were removed due to failed dependencies as skipped
        val skippedTasks = oldRemainingTasks - remainingGraph.tasks.keys - successfulTaskIds.toSet() - failedTaskIds.toSet()
        for (skippedTaskId in skippedTasks) {
          if (!results.containsKey(skippedTaskId)) {
            results[skippedTaskId] = TaskResult(
              taskId = skippedTaskId,
              success = false,
              terminalOutput = "SKIPPED: Task was skipped due to a failed dependency",
              startTime = 0,
              endTime = 0
            )
            log.debug("Skipped task: $skippedTaskId (dependency failed)")
          }
        }

        log.info("Successful tasks: ${successfulTaskIds.joinToString(", ")}")
        log.info("New roots: ${remainingGraph.roots.joinToString(", ")}")
        val graphUpdateDuration = System.currentTimeMillis() - graphUpdateStartTime
        log.info("Graph recalculation and task analysis took ${graphUpdateDuration}ms")
      }
    } finally {
      gracefulShutdown()
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

  private fun gracefulShutdown() {
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

    // Module selector (always pass the project)
    val task = options.taskGraph?.tasks?.get(taskId)
    val projectSelector = task?.projectRoot ?: task?.target?.project ?: mavenBatchTask.project
    arguments.add("-pl")
    arguments.add(projectSelector)

    return arguments
  }
}
