package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.server.artifact.Artifact
import at.yawk.javabrowser.server.artifact.ArtifactManager
import at.yawk.javabrowser.server.view.SourceFileView
import at.yawk.javabrowser.server.view.TypeSearchView
import com.fasterxml.jackson.databind.ObjectMapper
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.server.model.Resource
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import javax.ws.rs.HttpMethod
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * @author yawkat
 */
class ArtifactResourceBuilder(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper,
        private val bindingResolver: BindingResolver,
        private val artifactManager: ArtifactManager,
        val artifact: Artifact
) {
    fun registerOn(resourceConfig: ResourceConfig) {
        val builder = Resource.builder(artifact.id)
        builder.addChildResource("{sourceFile:.+\\.java}")
                .addMethod(HttpMethod.GET)
                .produces(MediaType.TEXT_HTML_TYPE)
                .handledBy { ctx ->
                    val sourceFile = ctx.uriInfo.pathParameters["sourceFile"]!!.single()

                    var annotatedSourceFile: AnnotatedSourceFile? = null
                    lateinit var dependencies: List<String>
                    dbi.inTransaction { conn: Handle, _ ->
                        val result = conn.select("select json from sourceFiles where artifactId = ? and path = ?",
                                artifact.id,
                                sourceFile)
                        if (result.isEmpty()) {
                            annotatedSourceFile = null
                        } else {
                            annotatedSourceFile = objectMapper.readValue(
                                    result.single()["json"] as ByteArray,
                                    AnnotatedSourceFile::class.java)
                            val dependencyDao = conn.attach(DependencyDao::class.java)
                            dependencies = dependencyDao.getDependencies(artifact.id)
                        }
                    }
                    if (annotatedSourceFile == null) {
                        Response.status(Response.Status.NOT_FOUND)
                    } else {
                        SourceFileView(
                                ArtifactId.split(artifact.id),
                                dependencies.mapNotNullTo(HashSet()) { artifactManager.findClosestMatch(it) } + artifact,
                                sourceFile,
                                bindingResolver,
                                annotatedSourceFile!!
                        )
                    }
                }
        builder
                .addMethod(HttpMethod.GET)
                .produces(MediaType.TEXT_HTML)
                .handledBy { _ ->
                    dbi.inTransaction { conn: Handle, _ ->
                        val dependencyDao = conn.attach(DependencyDao::class.java)
                        val dependencies = dependencyDao.getDependencies(artifact.id).map { dep ->
                            val prefix = artifactManager.findClosestPrefix(dep)
                            if (prefix == null) {
                                TypeSearchView.Dependency(null, dep)
                            } else {
                                TypeSearchView.Dependency(prefix, dep.removePrefix(prefix))
                            }
                        }
                        TypeSearchView(ArtifactId.split(artifact.id), dependencies)
                    }
                }
        val resource = builder.build()
        resourceConfig.registerResources(resource)
    }
}