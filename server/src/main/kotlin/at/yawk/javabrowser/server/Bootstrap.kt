package at.yawk.javabrowser.server

import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.dropwizard.Application
import io.dropwizard.assets.AssetsBundle
import io.dropwizard.jdbi.DBIFactory
import io.dropwizard.setup.Environment
import io.dropwizard.views.ViewBundle
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(Bootstrap::class.java)

class Bootstrap : Application<Config>() {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Bootstrap().run(*args)
        }
    }

    override fun initialize(bootstrap: io.dropwizard.setup.Bootstrap<Config>) {
        bootstrap.addBundle(ViewBundle<Config>())
        bootstrap.addBundle(AssetsBundle("/META-INF/resources/webjars", "/webjars", "", "webjars"))
        bootstrap.addBundle(AssetsBundle())
    }

    override fun run(configuration: Config, environment: Environment) {
        val objectMapper = environment.objectMapper
        objectMapper.registerModule(KotlinModule())

        val dataSource = configuration.database.build(environment.metrics(), "h2")
        val flyway = Flyway()
        flyway.dataSource = dataSource
        flyway.migrate()
        val dbi = DBIFactory().build(environment, configuration.database, dataSource, "h2")

        val bindingResolver = BindingResolver(dbi)
        val searchResource = SearchResource(dbi)

        val compiler = Compiler(dbi, objectMapper)
        val compileExecutor = Executors.newFixedThreadPool(configuration.compilerThreads)
        // flatten maven artifacts to one artifact per version, so that multiple versions can be compiled in parallel
        val artifacts = configuration.artifacts.flatMap {
            @Suppress("IfThenToElvis")
            if (it is Artifact.Maven) it.versions.map { v -> it.copy(versions = listOf(v)) } else listOf(it)
        }
        val artifactIds = ArrayList<String>()
        for (artifact in artifacts) {
            val artifactId = when (artifact) {
                is Artifact.OldJava -> "java/${artifact.version}"
                is Artifact.Java -> "java/${artifact.version}"
                is Artifact.Maven -> "${artifact.groupId}/${artifact.artifactId}/${artifact.versions.single()}"
            }
            artifactIds.add(artifactId)
            compileExecutor.execute {
                try {
                    when (artifact) {
                        is Artifact.OldJava -> compiler.compileOldJava(artifactId, artifact)
                        is Artifact.Java -> compiler.compileJava(artifactId, artifact)
                        is Artifact.Maven -> compiler.compileMaven(artifactId, artifact, artifact.versions.single())
                    }
                    log.info("Readying $artifactId...")

                    bindingResolver.invalidate()
                    searchResource.ready(artifactId)

                    log.info("$artifactId is ready")

                    // ask nicely for a gc to free up resources from compilation (MaxHeapFreeRatio)
                    System.gc()
                } catch (e: Exception) {
                    log.error("Failed to compile artifact {}", artifact, e)
                }
            }

            ArtifactResourceBuilder(dbi, objectMapper, bindingResolver, artifactId)
                    .registerOn(environment.jersey().resourceConfig)
        }
        compileExecutor.shutdown()

        Overview(artifactIds).registerOverviewResources(environment.jersey().resourceConfig)
        environment.jersey().register(searchResource)
        environment.jersey().register(ReferenceResource(dbi))
    }
}