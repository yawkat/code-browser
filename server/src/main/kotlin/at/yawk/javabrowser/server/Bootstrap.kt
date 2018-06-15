package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactManager
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

        val artifactManager = ArtifactManager()
        artifactManager.init(configuration.artifacts)
        val bindingResolver = BindingResolver(dbi, artifactManager)
        val searchResource = SearchResource(dbi)

        val compiler = Compiler(dbi, objectMapper)
        val compileExecutor = Executors.newFixedThreadPool(configuration.compilerThreads)
        val artifactIds = ArrayList<String>()
        for (artifact in artifactManager.artifacts) {
            artifactIds.add(artifact.id)
            compileExecutor.execute {
                try {
                    when (artifact.config) {
                        is ArtifactConfig.OldJava -> compiler.compileOldJava(artifact.id, artifact.config)
                        is ArtifactConfig.Java -> compiler.compileJava(artifact.id, artifact.config)
                        is ArtifactConfig.Maven -> compiler.compileMaven(artifact.id, artifact.config)
                    }
                    log.info("Readying ${artifact.id}...")

                    bindingResolver.invalidate()
                    searchResource.ready(artifact.id)

                    log.info("${artifact.id} is ready")

                    // ask nicely for a gc to free up resources from compilation (MaxHeapFreeRatio)
                    System.gc()
                } catch (e: Exception) {
                    log.error("Failed to compile artifact {}", artifact, e)
                }
            }

            ArtifactResourceBuilder(dbi, objectMapper, bindingResolver, artifactManager, artifact)
                    .registerOn(environment.jersey().resourceConfig)
        }
        compileExecutor.shutdown()

        Overview(artifactIds).registerOverviewResources(environment.jersey().resourceConfig)
        environment.jersey().register(searchResource)
        environment.jersey().register(ReferenceResource(dbi))
    }
}