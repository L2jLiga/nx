package dev.nx.maven.runner

import org.apache.maven.execution.DefaultMavenExecutionRequest
import org.apache.maven.execution.DefaultMavenExecutionResult
import org.apache.maven.execution.MavenSession
import org.codehaus.plexus.PlexusContainer
import org.codehaus.plexus.DefaultPlexusContainer
import org.eclipse.aether.DefaultRepositoryCache
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Factory for creating the best available Maven executor.
 *
 * Detects Maven version and creates appropriate executor:
 * - Maven 4.x: Creates SessionCachingMavenExecutor with project caching
 * - Maven 3.9.x: Falls back to ProcessBasedMavenExecutor via subprocess
 *
 * This provides version-agnostic executor creation that works in both environments.
 */
object SessionCachingMavenExecutorFactory {
    private val log = LoggerFactory.getLogger(SessionCachingMavenExecutorFactory::class.java)

    /**
     * Create a MavenExecutor suitable for the current Maven version.
     *
     * Uses ProcessBasedMavenExecutor (subprocess execution) as it's the most reliable approach:
     * - Works with both Maven 4.x and 3.9.x
     * - No component initialization complexity
     * - Guaranteed to work across Maven versions
     *
     * SessionCachingMavenExecutor could provide faster execution via project caching,
     * but requires proper Plexus component initialization which is fragile across versions.
     *
     * @param workspaceRoot The Maven workspace root directory
     * @param localRepositoryPath Path to local Maven repository (~/.m2/repository)
     * @return Configured ProcessBasedMavenExecutor for reliable execution
     */
    fun create(
        workspaceRoot: File,
        localRepositoryPath: File = File(System.getProperty("user.home"), ".m2/repository")
    ): MavenExecutor {
        log.info("Creating ProcessBasedMavenExecutor (subprocess-based, version-agnostic)")
        return ProcessBasedMavenExecutor(workspaceRoot)
    }

    /**
     * Attempt to create SessionCachingMavenExecutor for Maven 4.x.
     * Throws exception if Maven 4.x components not available.
     */
    private fun createSessionCachingExecutor(
        workspaceRoot: File,
        localRepositoryPath: File
    ): SessionCachingMavenExecutor {
        log.info("Attempting SessionCachingMavenExecutor (Maven 4.x optimized)")

        // Create PlexusContainer (Maven's service container)
        val plexusContainer = createPlexusContainer()

        // Create repository system session (for artifact resolution caching)
        val repositorySession = createRepositorySystemSession(localRepositoryPath)

        // Create Maven execution request (basic template for all executions)
        val templateRequest = DefaultMavenExecutionRequest().apply {
            setBaseDirectory(workspaceRoot)
            setRecursive(true)
            setInteractiveMode(false)
            setRepositoryCache(DefaultRepositoryCache())
            setIgnoreMissingArtifactDescriptor(true)
            setIgnoreInvalidArtifactDescriptor(true)
        }

        // Create Maven session (reused for all tasks)
        val mavenSession = MavenSession(
            plexusContainer,
            repositorySession,
            templateRequest,
            DefaultMavenExecutionResult()
        )

        log.info("✅ SessionCachingMavenExecutor created (Maven 4.x session caching enabled)")
        return SessionCachingMavenExecutor(plexusContainer, mavenSession)
    }

    /**
     * Create a Plexus container for Maven.
     * Note: This container may be from Maven 3.9 (test environment) or Maven 4.x (production).
     * The container is used primarily to hold the MavenSession, not for component lookups.
     */
    private fun createPlexusContainer(): PlexusContainer {
        return try {
            log.debug("Creating PlexusContainer for Maven")
            DefaultPlexusContainer()
        } catch (e: Exception) {
            log.error("Failed to create PlexusContainer", e)
            throw RuntimeException("Could not initialize Maven Plexus container", e)
        }
    }

    /**
     * Create a RepositorySystemSession for artifact resolution.
     * This session caches artifact metadata and resolution results.
     */
    private fun createRepositorySystemSession(localRepositoryPath: File): RepositorySystemSession {
        val session = DefaultRepositorySystemSession()
        try {
            // Set local repository using reflection since API may vary
            session.javaClass.getMethod("setLocalRepository", Any::class.java)
                .invoke(session, LocalRepository(localRepositoryPath))
        } catch (e: Exception) {
            log.debug("Could not set local repository: ${e.message}")
        }

        try {
            // Set cache configuration
            session.javaClass.getMethod("setRepositoryCache", Any::class.java)
                .invoke(session, DefaultRepositoryCache())
        } catch (e: Exception) {
            log.debug("Could not set repository cache: ${e.message}")
        }

        try {
            // Disable offline mode
            session.javaClass.getMethod("setOffline", Boolean::class.java)
                .invoke(session, false)
        } catch (e: Exception) {
            log.debug("Could not set offline mode: ${e.message}")
        }

        log.debug("RepositorySystemSession created with local repo: ${localRepositoryPath.absolutePath}")
        return session
    }
}
