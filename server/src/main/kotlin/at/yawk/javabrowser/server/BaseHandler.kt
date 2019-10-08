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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * @author yawkat
 */
class BaseHandler(private val dbi: DBI,
                  private val ftl: Ftl,
                  private val bindingResolver: BindingResolver,
                  private val objectMapper: ObjectMapper,
                  private val artifactIndex: ArtifactIndex,
                  private val declarationTreeHandler: DeclarationTreeHandler) : HttpHandler {
    private sealed class ParsedPath(val artifact: ArtifactNode) {
        class SourceFile(artifact: ArtifactNode, val sourceFilePath: String) : ParsedPath(artifact)
        class Artifact(artifact: ArtifactNode) : ParsedPath(artifact)
        class Root(artifact: ArtifactNode) : ParsedPath(artifact)
    }

    private fun parsePath(rawPath: String): ParsedPath {
        val path = rawPath.removePrefix("/").removeSuffix("/")
        val pathParts = if (path.isEmpty()) emptyList() else path.split('/')

        var node = artifactIndex.rootArtifact
        for ((i, pathPart) in pathParts.withIndex()) {
            val child = node.children[pathPart]
            if (child != null) {
                node = child
            } else {
                val sourceFileParts = pathParts.subList(i, pathParts.size)
                val sourceFilePath = sourceFileParts.joinToString("/")
                return ParsedPath.SourceFile(node, sourceFilePath)
            }
        }
        if (node.children.isEmpty()) {
            // artifact overview
            return ParsedPath.Artifact(node)
        } else {
            return ParsedPath.Root(node)
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val path = parsePath(exchange.relativePath)

        dbi.inTransaction { conn: Handle, _ ->
            val view = when (path) {
                is ParsedPath.SourceFile -> sourceFile(exchange, conn, path)
                is ParsedPath.Artifact -> TypeSearchView(
                        path.artifact,
                        getArtifactMetadata(conn, path.artifact),
                        listDependencies(conn, path.artifact.id).map {
                            buildDependencyInfo(conn, it)
                        },
                        declarationTreeHandler.packageTree(conn, path.artifact.id)
                )
                is ParsedPath.Root -> IndexView(path.artifact)
            }
            ftl.render(exchange, view)
        }
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

    private fun requestSourceFile(conn: Handle, parsedPath: ParsedPath.SourceFile): AnnotatedSourceFile {
        val result = conn.select("select json from sourceFiles where artifactId = ? and path = ?",
                parsedPath.artifact.id, parsedPath.sourceFilePath)
        if (result.isEmpty()) {
            throw HttpException(StatusCodes.NOT_FOUND, "No such source file")
        }
        return objectMapper.readValue(result.single()["json"] as ByteArray, AnnotatedSourceFile::class.java)
    }

    private fun sourceFile(exchange: HttpServerExchange,
                           conn: Handle,
                           parsedPath: ParsedPath.SourceFile): View {
        val diffWith = exchange.queryParameters["diff"]?.peekFirst()?.let { parsePath(it) }
        if (diffWith !is ParsedPath.SourceFile?) {
            throw HttpException(StatusCodes.BAD_REQUEST, "Can't diff with that")
        }

        val dependencies = listDependencies(conn, parsedPath.artifact.id)

        val sourceFile = requestSourceFile(conn, parsedPath)

        if (!exchange.isCrawler()) {
            // increment hit counter for this time bin
            val timestamp = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
            conn.update("insert into hits (timestamp, sourceFile, artifactId, hits) values (?, ?, ?, 1) on conflict (timestamp, sourceFile, artifactId) do update set hits = hits.hits + 1",
                    timestamp.toLocalDateTime(),
                    parsedPath.sourceFilePath,
                    parsedPath.artifact.id)
        }

        val alternatives = ArrayList<SourceFileView.Alternative>()
        fun tryExactMatch(path: String) {
            conn.createQuery("select artifactId, path from sourceFiles where path = ?")
                    .bind(0, path)
                    .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2)) }
                    .forEach { alternatives.add(it) }
        }

        tryExactMatch(parsedPath.sourceFilePath)

        if (parsedPath.artifact.idList[0] == "java") {
            if (parsedPath.artifact.idList[1].toInt() < 9) {
                conn.createQuery("select artifactId, path from sourceFiles where artifactId like 'java/%' and path like ?")
                        .bind(0, "%/${parsedPath.sourceFilePath}")
                        .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2)) }
                        .forEach { alternatives.add(it) }
            } else {
                // try without module
                tryExactMatch(parsedPath.sourceFilePath.substring(parsedPath.sourceFilePath.indexOf('/') + 1))
            }
        }

        val separator = parsedPath.sourceFilePath.lastIndexOf('/')
        return SourceFileView(
                artifactId = parsedPath.artifact,
                classpath =  dependencies.toSet() + parsedPath.artifact.id,
                classpathOld = diffWith?.let { listDependencies(conn, it.artifact.id).toSet() + it.artifact.id },
                sourceFilePathDir = parsedPath.sourceFilePath.substring(0, separator + 1),
                sourceFilePathFile = parsedPath.sourceFilePath.substring(separator + 1),
                alternatives = alternatives,
                artifactMetadata = getArtifactMetadata(conn, parsedPath.artifact),
                declarations = declarationTreeHandler.declarationTree(parsedPath.artifact.id, sourceFile),
                bindingResolver = bindingResolver,
                sourceFile = sourceFile,
                sourceFileOld = diffWith?.let { requestSourceFile(conn, it as ParsedPath.SourceFile) }
        )
    }

    private fun listDependencies(conn: Handle, artifactId: String): List<String> {
        return conn.createQuery("select toArtifactId from dependencies where fromArtifactId = ?")
                .bind(0, artifactId)
                .map(SingleColumnResultSetMapper.STRING)
                .toList()
    }
}