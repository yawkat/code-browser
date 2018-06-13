package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.server.view.SourceFileView
import at.yawk.javabrowser.server.view.TypeSearchView
import com.fasterxml.jackson.databind.ObjectMapper
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.server.model.Resource
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus
import java.net.URI
import javax.ws.rs.HttpMethod
import javax.ws.rs.RedirectionException
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

/**
 * @author yawkat
 */
class ArtifactResourceBuilder(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper,
        private val bindingResolver: BindingResolver,
        val artifactId: String
) {
    fun registerOn(resourceConfig: ResourceConfig) {
        val builder = Resource.builder(artifactId)
        builder.addChildResource("{sourceFile:.+\\.java}")
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
                                result.single()["json"] as ByteArray,
                                AnnotatedSourceFile::class.java)
                    }
                    if (annotatedSourceFile == null) {
                        Response.status(Response.Status.NOT_FOUND)
                    } else {
                        SourceFileView(ArtifactId.split(artifactId),
                                sourceFile,
                                bindingResolver,
                                annotatedSourceFile)
                    }
                }
        builder
                .addMethod(HttpMethod.GET)
                .produces(MediaType.TEXT_HTML)
                .handledBy { ctx ->
                    dbi.inTransaction { conn: Handle, _ ->
                        val dependencyDao = conn.attach(DependencyDao::class.java)
                        val dependencies = dependencyDao.getDependencies(artifactId)
                        val existingDependencies = dependencyDao.getExistingDependencies(artifactId)
                        TypeSearchView(ArtifactId.split(artifactId), dependencies, existingDependencies)
                    }
                }
        val resource = builder.build()
        resourceConfig.registerResources(resource)
    }
}