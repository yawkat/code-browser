package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsQuery
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.server.artifact.ArtifactNode
import at.yawk.javabrowser.server.view.FullTextSearchResultView
import com.google.common.annotations.VisibleForTesting
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
        val realmName = exchange.queryParameters["realm"]?.peekFirst() ?: Realm.SOURCE.name
        val realm = Realm.parse(realmName) ?: throw HttpException(404, "Unknown realm")
        val searchArtifact = exchange.queryParameters["artifactId"]?.peekFirst()?.let {
            artifactIndex.allArtifactsByStringId[it] ?: throw HttpException(404, "No such artifact")
        }
        val useSymbolsParameter = exchange.queryParameters["useSymbols"]?.peekFirst()?.toBoolean()

        dbi.withHandle { conn: Handle ->
            ftl.render(exchange, handleRequest(
                    query = query,
                    realm = realm,
                    searchArtifact = searchArtifact,
                    useSymbolsParameter = useSymbolsParameter,
                    conn = conn
            ))
            conn.rollback() // don't persist array_accum
        }
    }

    @VisibleForTesting
    internal fun handleRequest(
            query: String,
            realm: Realm,
            searchArtifact: ArtifactNode?,
            useSymbolsParameter: Boolean?,

            conn: Handle
    ): FullTextSearchResultView {
        var tokens = Tokenizer.tokenize(query)
        val useSymbols = useSymbolsParameter ?: tokens.any { it.symbol }
        // null check avoids a pointless filter call when no symbols are present anyway
        if (useSymbolsParameter != null && !useSymbols) {
            tokens = tokens.filter { !it.symbol }
        }

        val tsQuery: TsQuery = TsQuery.Phrase(tokens.map { TsQuery.Term(it.text) }.toList())
        val table = if (useSymbols) "source_file_lexemes" else "source_file_lexemes_no_symbols"

        class FileResult(
                val realm: Realm,
                val artifactId: Long,
                val artifactStringId: String,
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
                    realm = realm,
                    artifactId = artifactStringId,
                    path = path,
                    classpath = conn.attach(DependencyDao::class.java).getDependencies(artifactId).toSet(),
                    partial = partial
            )
        }

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
select 
   source_file.artifact_id artifactId,
   source_file.path sourceFile,
   array_agg(cast(lexemes as text)) lexemes,
   array_accum(starts)  starts,
   array_accum(lengths) lengths,
   text,
   annotations
from $table sfl
natural left join source_file
where sfl.realm = :realm and lexemes @@ cast(:query as tsquery) ${if (searchArtifact == null) "" else "and sfl.artifact_id = :searchArtifact"}
-- group by primary key
group by source_file.realm, source_file.artifact_id, source_file.source_file_id
                """
        )
                // prepare immediately so that int arrays are transmitted as binary
                .addStatementCustomizer(PrepareStatementImmediately)
                .setFetchSize(50)
                .bind("realm", realm.id)
                .bind("query", tsQuery.toString())
                .bind("searchArtifact", searchArtifact?.dbId)
                .map { _, r, _ ->
                    val artifactId = r.getLong("artifactId")
                    @Suppress("UNCHECKED_CAST")
                    FileResult(
                            realm,
                            artifactId,
                            artifactIndex.allArtifactsByDbId[artifactId].stringId,
                            r.getString("sourceFile"),
                            (r.getArray("lexemes").array as Array<*>).map {
                                val vc = TsVector()
                                vc.addFromSql(it as String)
                                vc
                            },
                            (r.getArray("starts").array as Array<Int>).toIntArray(),
                            (r.getArray("lengths").array as Array<Int>).toIntArray(),
                            ServerSourceFile(
                                    textBytes = r.getBytes("text"),
                                    annotationBytes = r.getBytes("annotations")
                            )
                    ).toView(conn)
                }
                .iterator()

        return FullTextSearchResultView(query, realm, searchArtifact, itr)
    }
}