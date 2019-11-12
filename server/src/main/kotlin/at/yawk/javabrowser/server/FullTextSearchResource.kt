package at.yawk.javabrowser.server

import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsQuery
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.server.view.FullTextSearchResultView
import com.fasterxml.jackson.databind.ObjectMapper
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import javax.inject.Inject

/**
 * @author yawkat
 */
class FullTextSearchResource @Inject constructor(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper,
        private val ftl: Ftl,
        private val bindingResolver: BindingResolver,
        private val artifactIndex: ArtifactIndex
) : HttpHandler {
    companion object {
        const val PATTERN = "/fts"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = exchange.queryParameters["query"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Query not given")
        val searchArtifact = exchange.queryParameters["artifactId"]?.peekFirst()?.let {
            val parsed = artifactIndex.parse(it)
            if (parsed.remainingPath != null) throw HttpException(404, "No such artifact")
            parsed.node
        }

        var tokens = Tokenizer.tokenize(query)
        val useSymbolsParameter = exchange.queryParameters["useSymbols"]?.peekFirst()?.toBoolean()
        val useSymbols = useSymbolsParameter ?: tokens.any { it.symbol }
        // null check avoids a pointless filter call when no symbols are present anyway
        if (useSymbolsParameter != null && !useSymbols) {
            tokens = tokens.filter { !it.symbol }
        }

        val tsQuery: TsQuery = TsQuery.Phrase(tokens.map { TsQuery.Term(it.text) }.toList())
        val table = if (useSymbols) "sourceFileLexemes" else "sourceFileLexemesNoSymbols"

        class FileResult(
                val artifactId: String,
                val path: String,
                lexemeVectors: List<TsVector>,
                starts: IntArray,
                lengths: IntArray,
                sourceFile: ServerSourceFile
        ) {
            val partial = SourceFilePrinter.Partial(sourceFile)

            init {
                var arrayOffset = 0
                for (lexemeVector in lexemeVectors) {
                    val matchPositions = lexemeVector.findMatchPositions(tsQuery)
                            ?: throw AssertionError("inconsistency between postgres and findMatchPositions? query: $$$tsQuery$$ vector: $$$lexemeVector$$")
                    matchPositions.forEach {
                        val start = starts[it + arrayOffset]
                        partial.addInterest(start, start + lengths[it + arrayOffset])
                    }
                    arrayOffset += lexemeVector.size
                }
            }

            fun toView(conn: Handle) = FullTextSearchResultView.SourceFileResult(
                    bindingResolver,
                    artifactId = artifactId,
                    path = path,
                    classpath = conn.attach(DependencyDao::class.java).getDependencies(artifactId).toSet(),
                    partial = partial
            )
        }

        dbi.withHandle { conn: Handle ->
            conn.begin()
            conn.update("""
CREATE AGGREGATE array_accum (anyarray)
    (
    sfunc = array_cat,
    stype = anyarray,
    initcond = '{}'
    )
            """)

            val itr = conn.createQuery(
                    """
select sourceFiles.artifactId artifactId,
       sourceFiles.path sourceFile,
       array_agg(cast(lexemes as text)) lexemes,
       array_accum(starts)  starts,
       array_accum(lengths) lengths,
       text,
       annotations
from $table sfl
         left join sourceFiles on sfl.artifactId = sourceFiles.artifactId and sfl.sourceFile = sourceFiles.path
where lexemes @@ cast(:query as tsquery) and (:searchArtifact is null or sfl.artifactId = :searchArtifact)
group by sourceFiles.artifactId, sourceFiles.path
                    """
            )
                    // prepare immediately so that int arrays are transmitted as binary
                    .addStatementCustomizer(PrepareStatementImmediately)
                    .setFetchSize(50)
                    .bind("query", tsQuery.toString())
                    .bind("searchArtifact", searchArtifact?.id)
                    .map { _, r, _ ->
                        @Suppress("UNCHECKED_CAST")
                        FileResult(
                                r.getString("artifactId"),
                                r.getString("sourceFile"),
                                (r.getArray("lexemes").array as Array<*>).map {
                                    val vc = TsVector()
                                    vc.addFromSql(it as String)
                                    vc
                                },
                                (r.getArray("starts").array as Array<Int>).toIntArray(),
                                (r.getArray("lengths").array as Array<Int>).toIntArray(),
                                ServerSourceFile(
                                        objectMapper,
                                        textBytes = r.getBytes("text"),
                                        annotationBytes = r.getBytes("annotations")
                                )
                        ).toView(conn)
                    }
                    .iterator()

            ftl.render(exchange, FullTextSearchResultView(query, searchArtifact, itr))

            conn.rollback() // don't persist array_accum
        }
    }
}