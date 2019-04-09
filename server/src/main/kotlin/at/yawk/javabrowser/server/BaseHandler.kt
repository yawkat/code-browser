package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.server.artifact.ArtifactNode
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
import org.skife.jdbi.v2.TransactionStatus
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * @author yawkat
 */
class BaseHandler(private val dbi: DBI,
                  private val ftl: Ftl,
                  private val bindingResolver: BindingResolver,
                  private val objectMapper: ObjectMapper) : HttpHandler {
    private val rootArtifact = dbi.inTransaction { conn: Handle, _: TransactionStatus ->
        ArtifactNode.build(conn.select("select id from artifacts").map { it["id"] as String })
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val path = exchange.relativePath.removePrefix("/").removeSuffix("/")
        val pathParts = if (path.isEmpty()) emptyList() else path.split('/')

        val view = dbi.inTransaction { conn: Handle, _ ->
            var node = rootArtifact
            for ((i, pathPart) in pathParts.withIndex()) {
                val child = node.children[pathPart]
                if (child != null) {
                    node = child
                } else {
                    val sourceFileParts = pathParts.subList(i, pathParts.size)
                    val sourceFilePath = sourceFileParts.joinToString("/")
                    return@inTransaction sourceFile(exchange, conn, node, sourceFilePath)
                }
            }
            if (node.children.isEmpty()) {
                // artifact overview
                return@inTransaction TypeSearchView(node,
                        getArtifactMetadata(conn, node),
                        listDependencies(conn, node.id).map {
                            buildDependencyInfo(conn, it)
                        })
            } else {
                return@inTransaction IndexView(node)
            }
        }
        ftl.render(exchange, view)
    }

    private fun getArtifactMetadata(conn: Handle, artifact: ArtifactNode): ArtifactMetadata {
        val bytes = conn.select("select metadata from artifacts where id = ?", artifact.id)
                .single()["metadata"] as ByteArray
        return objectMapper.readValue(bytes, ArtifactMetadata::class.java)
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

    private fun sourceFile(exchange: HttpServerExchange,
                           conn: Handle,
                           artifactPath: ArtifactNode,
                           sourceFilePath: String): View {
        val dependencies = listDependencies(conn, artifactPath.id)

        val result = conn.select("select json from sourceFiles where artifactId = ? and path = ?",
                artifactPath.id, sourceFilePath)
        if (result.isEmpty()) {
            throw HttpException(StatusCodes.NOT_FOUND, "No such source file")
        }

        if (!exchange.isCrawler()) {
            // increment hit counter for this time bin
            val timestamp = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
            conn.update("insert into hits (timestamp, sourceFile, artifactId, hits) values (?, ?, ?, 1) on conflict (timestamp, sourceFile, artifactId) do update set hits = hits.hits + 1",
                    timestamp.toLocalDateTime(),
                    sourceFilePath,
                    artifactPath.id)
        }

        val alternatives = ArrayList<SourceFileView.Alternative>()
        fun tryExactMatch(path: String) {
            conn.createQuery("select artifactId, path from sourceFiles where path = ?")
                    .bind(0, path)
                    .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2)) }
                    .forEach { alternatives.add(it) }
        }

        tryExactMatch(sourceFilePath)

        if (artifactPath.idList[0] == "java") {
            if (artifactPath.idList[1].toInt() < 9) {
                conn.createQuery("select artifactId, path from sourceFiles where artifactId like 'java/%' and path like ?")
                        .bind(0, "%/$sourceFilePath")
                        .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2)) }
                        .forEach { alternatives.add(it) }
            } else {
                // try without module
                tryExactMatch(sourceFilePath.substring(sourceFilePath.indexOf('/') + 1))
            }
        }

        val sourceFile = objectMapper.readValue(
                result.single()["json"] as ByteArray,
                AnnotatedSourceFile::class.java)

        val separator = sourceFilePath.lastIndexOf('/')
        return SourceFileView(
                artifactId = artifactPath,
                classpath =  dependencies.toSet() + artifactPath.id,
                sourceFilePathDir = sourceFilePath.substring(0, separator + 1),
                sourceFilePathFile = sourceFilePath.substring(separator + 1),
                alternatives = alternatives,
                artifactMetadata = getArtifactMetadata(conn, artifactPath),
                bindingResolver = bindingResolver,
                sourceFile = sourceFile
        )
    }

    private fun listDependencies(conn: Handle, artifactId: String): List<String> {
        return conn.createQuery("select toArtifactId from dependencies where fromArtifactId = ?")
                .bind(0, artifactId)
                .map(SingleColumnResultSetMapper.STRING)
                .toList()
    }
}