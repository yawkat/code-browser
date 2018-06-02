package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.server.view.SourceFileView
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.dropwizard.Application
import io.dropwizard.assets.AssetsBundle
import io.dropwizard.jdbi.DBIFactory
import io.dropwizard.setup.Environment
import io.dropwizard.views.ViewBundle
import org.flywaydb.core.Flyway
import org.glassfish.jersey.server.model.Resource
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.sql.Blob
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.ws.rs.HttpMethod
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

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

        val compiler = Compiler(dbi, objectMapper)
        val compileExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        // flatten maven artifacts to one artifact per version, so that multiple versions can be compiled in parallel
        val artifacts = configuration.artifacts.flatMap {
            @Suppress("IfThenToElvis")
            if (it is Artifact.Maven) it.versions.map { v -> it.copy(versions = listOf(v)) } else listOf(it)
        }
        for (artifact in artifacts) {
            val artifactId = when (artifact) {
                is Artifact.OldJava -> "java/${artifact.version}"
                is Artifact.Java -> "java.base/${artifact.version}"
                is Artifact.Maven -> "${artifact.groupId}/${artifact.artifactId}/${artifact.versions.single()}"
            }
            compileExecutor.execute {
                try {
                    when (artifact) {
                        is Artifact.OldJava -> compiler.compileOldJava(artifactId, artifact)
                        is Artifact.Java -> compiler.compileJava(artifactId, artifact)
                        is Artifact.Maven -> compiler.compileMaven(artifactId, artifact, artifact.versions.single())
                    }

                    bindingResolver.invalidate()

                    log.info("$artifactId is ready")
                } catch (e: Exception) {
                    log.error("Failed to compile artifact {}", artifact, e)
                }
            }

            val builder = Resource.builder(artifactId)
            builder.addChildResource("{sourceFile:.+}")
                    .addMethod(HttpMethod.GET)
                    .produces(MediaType.TEXT_HTML_TYPE)
                    .handledBy { ctx ->
                        val sourceFile = ctx.uriInfo.pathParameters["sourceFile"]!!.single()

                        val annotatedSourceFile = dbi.inTransaction { conn: Handle, _ ->
                            val result = conn.select("select json from sourceFiles where artifactId = ? and path = ?",
                                    artifactId,
                                    sourceFile)
                            if (result.isEmpty()) null
                            else objectMapper.readValue(
                                    (result.single()["json"] as Blob).binaryStream,
                                    AnnotatedSourceFile::class.java)
                        }
                        if (annotatedSourceFile == null) {
                            Response.status(Response.Status.NOT_FOUND)
                        } else {
                            SourceFileView(artifactId,
                                    sourceFile,
                                    bindingResolver,
                                    annotatedSourceFile)
                        }
                    }
            val resource = builder.build()
            environment.jersey().resourceConfig.registerResources(resource)
        }
        compileExecutor.shutdown()
    }
}