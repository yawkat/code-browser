package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import at.yawk.javabrowser.server.view.DeclarationNode
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
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.function.Function
import javax.inject.Inject

/**
 * @author yawkat
 */
class BaseHandler @Inject constructor(
        private val dbi: DBI,
        private val ftl: Ftl,
        private val bindingResolver: BindingResolver,
        private val objectMapper: ObjectMapper,
        private val artifactIndex: ArtifactIndex,
        private val declarationTreeHandler: DeclarationTreeHandler,
        private val siteStatisticsService: SiteStatisticsService,
        private val aliasIndex: AliasIndex,
        private val metadataCache: ArtifactMetadataCache
) : HttpHandler {
    private sealed class ParsedPath(val artifact: ArtifactNode) {
        class SourceFile(artifact: ArtifactNode, val sourceFilePath: String) : ParsedPath(artifact)
        class LeafArtifact(artifact: ArtifactNode) : ParsedPath(artifact)
        class Group(artifact: ArtifactNode) : ParsedPath(artifact)
    }

    private fun parsePath(rawPath: String): ParsedPath {
        val parsed = artifactIndex.parse(rawPath)
        when {
            parsed.remainingPath != null ->
                return ParsedPath.SourceFile(parsed.node, parsed.remainingPath)
            parsed.node.children.isEmpty() ->
                // artifact overview
                return ParsedPath.LeafArtifact(parsed.node)
            else ->
                return ParsedPath.Group(parsed.node)
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val path = parsePath(exchange.relativePath)

        dbi.inTransaction { conn: Handle, _ ->
            val view = when (path) {
                is ParsedPath.SourceFile -> sourceFile(exchange, conn, path)
                is ParsedPath.LeafArtifact -> typeSearch(exchange, conn, path)
                is ParsedPath.Group -> IndexView(path.artifact, siteStatisticsService.statistics)
            }
            ftl.render(exchange, view)
        }
    }

    private fun typeSearch(exchange: HttpServerExchange, conn: Handle, path: ParsedPath): TypeSearchView {
        val diffWith = exchange.queryParameters["diff"]?.peekFirst()?.let { parsePath(it) }
        if (diffWith !is ParsedPath.LeafArtifact?) {
            throw HttpException(StatusCodes.BAD_REQUEST, "Can't diff with that")
        }

        val topLevelPackages: Iterator<DeclarationNode>
        if (diffWith == null) {
            topLevelPackages = declarationTreeHandler.packageTree(conn, path.artifact.id)
        } else {
            topLevelPackages = declarationTreeHandler.packageTreeDiff(conn, diffWith.artifact.id, path.artifact.id)
        }
        val alternatives = path.artifact.parent!!.children.values
                .sortedWith(Comparator.comparing(Function { it.id }, VersionComparator))
                .map {
                    val cmp = VersionComparator.compare(it.id, path.artifact.id)
                    when {
                        cmp < 0 -> TypeSearchView.Alternative(it, "/${it.id}?diff=${URLEncoder.encode(path.artifact.id, "UTF-8")}")
                        cmp > 0 -> TypeSearchView.Alternative(it, "/${path.artifact.id}?diff=${URLEncoder.encode(it.id, "UTF-8")}")
                        else -> TypeSearchView.Alternative(it, null)
                    }
                }
        return TypeSearchView(
                path.artifact,
                diffWith?.artifact,
                metadataCache.getArtifactMetadata(path.artifact),
                conn.attach(DependencyDao::class.java).getDependencies(path.artifact.id).map {
                    buildDependencyInfo(it)
                },
                topLevelPackages,
                alternatives
        )
    }

    private fun buildDependencyInfo(it: String): TypeSearchView.Dependency {
        if (artifactIndex.allArtifacts.containsKey(it)) {
            return TypeSearchView.Dependency(it, "", null)
        }

        val matchingAlias = aliasIndex.findAliasedTo(it)

        val parts = it.split("/")
        for (i in parts.size - 1 downTo 1) {
            val prefix = parts.subList(0, i).joinToString("/")

            val node = artifactIndex.allArtifacts[prefix]
            if (node != null) {
                return TypeSearchView.Dependency(
                        "$prefix/",
                        parts.subList(i, parts.size).joinToString("/"),
                        matchingAlias)
            }
        }
        return TypeSearchView.Dependency(null, it, matchingAlias)
    }

    private fun requestSourceFile(conn: Handle, parsedPath: ParsedPath.SourceFile): ServerSourceFile {
        val result = conn.select("select text, annotations from sourceFiles where artifactId = ? and path = ?",
                parsedPath.artifact.id, parsedPath.sourceFilePath)
        if (result.isEmpty()) {
            throw HttpException(StatusCodes.NOT_FOUND, "No such source file")
        }
        val sourceFile = ServerSourceFile(objectMapper,
                result.single()["text"] as ByteArray,
                result.single()["annotations"] as ByteArray)
        sourceFile.bakeAnnotations() // need two passes - one for the side bar, one for the actual code.
        return sourceFile
    }

    private fun sourceFile(exchange: HttpServerExchange,
                           conn: Handle,
                           parsedPath: ParsedPath.SourceFile): View {
        val diffWith = exchange.queryParameters["diff"]?.peekFirst()?.let { parsePath(it) }
        if (diffWith !is ParsedPath.SourceFile?) {
            throw HttpException(StatusCodes.BAD_REQUEST, "Can't diff with that")
        }

        val dependencies = conn.attach(DependencyDao::class.java).getDependencies(parsedPath.artifact.id)

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
                    .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2), null) }
                    .forEach { alternatives.add(it) }
        }

        tryExactMatch(parsedPath.sourceFilePath)

        if (parsedPath.artifact.idList[0] == "java") {
            if (parsedPath.artifact.idList[1].toInt() < 9) {
                conn.createQuery("select artifactId, path from sourceFiles where artifactId like 'java/%' and path like ?")
                        .bind(0, "%/${parsedPath.sourceFilePath}")
                        .map { _, r, _ -> SourceFileView.Alternative(r.getString(1), r.getString(2), null) }
                        .forEach { alternatives.add(it) }
            } else {
                // try without module
                tryExactMatch(parsedPath.sourceFilePath.substring(parsedPath.sourceFilePath.indexOf('/') + 1))
            }
        }

        alternatives.sortWith(Comparator.comparing(Function { it.artifactId }, VersionComparator))
        alternatives.replaceAll {
            if (it.artifactId == parsedPath.artifact.id) {
                it
            } else {
                val newerAlternative = VersionComparator.compare(it.artifactId, parsedPath.artifact.id) < 0
                var new = "/${parsedPath.artifact.id}/${parsedPath.sourceFilePath}"
                var old = "/${it.artifactId}/${it.sourceFilePath}"
                if (newerAlternative) {
                    val tmp = new
                    new = old
                    old = tmp
                }
                it.copy(diffPath = "$new?diff=${URLEncoder.encode(old, "UTF-8")}")
            }
        }

        val declarations: Iterator<DeclarationNode>
        val oldInfo: SourceFileView.FileInfo?
        if (diffWith == null) {
            oldInfo = null
            declarations = declarationTreeHandler.sourceDeclarationTree(parsedPath.artifact.id, sourceFile.annotations)
        } else {
            @Suppress("USELESS_CAST")
            oldInfo = SourceFileView.FileInfo(
                    artifactId = diffWith.artifact,
                    sourceFile = requestSourceFile(conn, diffWith as ParsedPath.SourceFile),
                    classpath = conn.attach(DependencyDao::class.java).getDependencies(diffWith.artifact.id).toSet() + diffWith.artifact.id,
                    sourceFilePath = diffWith.sourceFilePath
            )
            declarations = DeclarationTreeDiff.diffUnordered(
                    declarationTreeHandler.sourceDeclarationTree(diffWith.artifact.id, oldInfo.sourceFile.annotations),
                    declarationTreeHandler.sourceDeclarationTree(parsedPath.artifact.id, sourceFile.annotations)
            )
        }

        return SourceFileView(
                newInfo = SourceFileView.FileInfo(
                        artifactId = parsedPath.artifact,
                        classpath = dependencies.toSet() + parsedPath.artifact.id,
                        sourceFile = sourceFile,
                        sourceFilePath = parsedPath.sourceFilePath
                ),
                oldInfo = oldInfo,
                alternatives = alternatives,
                artifactMetadata = metadataCache.getArtifactMetadata(parsedPath.artifact),
                declarations = declarations,
                bindingResolver = bindingResolver
        )
    }
}