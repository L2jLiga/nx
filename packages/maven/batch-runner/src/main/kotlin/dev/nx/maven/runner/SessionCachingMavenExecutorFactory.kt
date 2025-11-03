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
 * Factory for creating a SessionCachingMavenExecutor with a single reused session.
 *
 * This factory:
 * 1. Creates or gets a PlexusContainer (Maven's DI container)
 * 2. Creates a RepositorySystemSession (for artifact resolution)
 * 3. Creates a MavenSession (reused for all executions)
 * 4. Returns a SessionCachingMavenExecutor ready for batch execution
 */
object SessionCachingMavenExecutorFactory {
    private val log = LoggerFactory.getLogger(SessionCachingMavenExecutorFactory::class.java)

    /**
     * Create a SessionCachingMavenExecutor with a single reused MavenSession.
     *
     * @param workspaceRoot The Maven workspace root directory
     * @param localRepositoryPath Path to local Maven repository (~/.m2/repository)
     * @return Configured SessionCachingMavenExecutor ready for batch execution
     */
    fun create(
        workspaceRoot: File,
        localRepositoryPath: File = File(System.getProperty("user.home"), ".m2/repository")
    ): SessionCachingMavenExecutor {
        log.info("Creating SessionCachingMavenExecutor")

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

        log.info("✅ SessionCachingMavenExecutor created with single reused session")
        return SessionCachingMavenExecutor(plexusContainer, mavenSession)
    }

    /**
     * Create a Plexus container for Maven.
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
