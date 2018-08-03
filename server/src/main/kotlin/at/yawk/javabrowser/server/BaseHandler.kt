package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.server.artifact.ArtifactPath
import at.yawk.javabrowser.server.view.IndexView
import at.yawk.javabrowser.server.view.SourceFileView
import at.yawk.javabrowser.server.view.TypeSearchView
import at.yawk.javabrowser.server.view.View
import com.fasterxml.jackson.databind.ObjectMapper
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle

/**
 * @author yawkat
 */
class BaseHandler(private val dbi: DBI,
                  private val ftl: Ftl,
                  private val bindingResolver: BindingResolver,
                  private val objectMapper: ObjectMapper) : HttpHandler {
    override fun handleRequest(exchange: HttpServerExchange) {
        val path = exchange.relativePath.removePrefix("/").removeSuffix("/")
        val pathParts = if (path.isEmpty()) emptyList() else path.split('/')

        val view = dbi.inTransaction { conn: Handle, _ ->
            val pathNodes = ArrayList<ArtifactPath.Node>()

            for (i in 0..pathParts.size) {
                val exact = pathParts.subList(0, i).joinToString("/")
                val query = conn.createQuery("select distinct split_part(id, '/', ?) from artifacts where id like ?")
                        .bind(0, i + 1)
                        // too lazy to escape LIKE characters... what could go wrong?
                        .bind(1, if (i == 0) "%" else "$exact/%")
                        .map(SingleColumnResultSetMapper.STRING)
                        .toList()
                if (query.isEmpty()) {
                    // this might be a concrete artifact

                    val count = conn.createQuery("select 1 from artifacts where id = ?")
                            .bind(0, exact)
                            .count()
                    if (count == 0) {
                        throw HttpException(StatusCodes.NOT_FOUND, "No such artifact")
                    }

                    if (i == pathParts.size) {
                        // top level
                        val artifactPath = ArtifactPath(pathNodes)
                        return@inTransaction TypeSearchView(artifactPath,
                                listDependencies(conn, artifactPath.id).map {
                                    buildDependencyInfo(conn, it)
                                })
                    } else {
                        val sourceFilePath = pathParts.subList(i, pathParts.size).joinToString("/")

                        return@inTransaction sourceFile(conn, ArtifactPath(pathNodes), sourceFilePath)
                    }
                } else {
                    pathNodes.add(ArtifactPath.Node(pathParts.getOrNull(i), query))
                }
            }
            return@inTransaction IndexView(ArtifactPath(pathNodes))
        }
        ftl.render(exchange, view)
    }

    private fun buildDependencyInfo(conn: Handle, it: String): TypeSearchView.Dependency {
        val parts = it.split("/")
        for (i in parts.size downTo 1) {
            var prefix = parts.subList(0, i).joinToString("/")
            if (i != parts.size) prefix += "/"

            if (conn.createQuery("select count(*) from artifacts where id like ?")
                            .bind(0, prefix + (if (i == parts.size) "" else "%"))
                            .map(SingleColumnResultSetMapper.INT)
                            .single() > 0) {
                return TypeSearchView.Dependency(prefix, parts.subList(i, parts.size).joinToString("/"))
            }
        }
        return TypeSearchView.Dependency(null, it)
    }

    private fun sourceFile(conn: Handle, artifactPath: ArtifactPath, sourceFilePath: String): View {
        val dependencies = listDependencies(conn, artifactPath.id)

        val result = conn.select("select json from sourceFiles where artifactId = ? and path = ?",
                artifactPath.id, sourceFilePath)
        if (result.isEmpty()) {
            throw HttpException(StatusCodes.NOT_FOUND, "No such source file")
        }
        val sourceFile = objectMapper.readValue(
                result.single()["json"] as ByteArray,
                AnnotatedSourceFile::class.java)

        return SourceFileView(
                artifactPath,
                dependencies.toSet(),
                sourceFilePath,
                bindingResolver,
                sourceFile
        )
    }

    private fun listDependencies(conn: Handle, artifactId: String): List<String> {
        return conn.createQuery("select toArtifactId from dependencies where fromArtifactId = ?")
                .bind(0, artifactId)
                .map(SingleColumnResultSetMapper.STRING)
                .toList()
    }
}