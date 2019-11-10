package at.yawk.javabrowser.server

import at.yawk.javabrowser.IntRangeSet
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsQuery
import at.yawk.javabrowser.TsVector
import com.fasterxml.jackson.databind.ObjectMapper
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.StatusCodes
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.lang.AssertionError

/**
 * @author yawkat
 */
class FullTextSearchResource(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper
) : HttpHandler {
    companion object {
        const val PATTERN = "/fts/{query}"

        private const val CONTEXT_LINES = 2
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = (exchange.queryParameters["query"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Query not given"))

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
                val lexemeVectors: List<TsVector>,
                val starts: IntArray,
                val lengths: IntArray,
                val sourceFile: ServerSourceFile
        ) {
            val displaySet = IntRangeSet()

            val relevantAnnotations = sourceFile.annotations.filter {
                displaySet.intersects(it.start, it.end)
            }

            init {
                var arrayOffset = 0
                for (lexemeVector in lexemeVectors) {
                    val matchPositions = lexemeVector.findMatchPositions(tsQuery)
                            ?: throw AssertionError("inconsistency between postgres and findMatchPositions?")
                    for (i in 0 until matchPositions.size()) {
                        val start = starts[i + arrayOffset]
                        displaySet.add(start, start + lengths[i + arrayOffset])
                    }
                    arrayOffset += lexemeVector.size
                }

                expandDisplayToLines()
            }

            fun expandDisplayToLines() {
                var lineStart = 0
                val backlog = IntLists.mutable.empty()
                var contextLead = 0
                while (lineStart < sourceFile.text.length) {
                    var lineEnd = sourceFile.text.indexOf('\n', lineStart)
                    if (lineEnd == -1) lineEnd = sourceFile.text.length

                    when {
                        // check if line contains a highlight
                        displaySet.intersects(lineStart, lineEnd) -> {
                            displaySet.add(if (backlog.isEmpty) lineStart else backlog[0], lineEnd)
                            backlog.clear()
                            contextLead = CONTEXT_LINES
                        }
                        // is this line just after a highlight?
                        contextLead > 0 -> {
                            displaySet.add(lineStart, lineEnd)
                            contextLead--
                        }
                        // else, add it to the backlog - a highlight below might still include it.
                        else -> {
                            if (backlog.size() > CONTEXT_LINES) backlog.removeAtIndex(0)
                            backlog.add(lineStart)
                        }
                    }

                    lineStart = lineEnd
                }
            }
        }

        dbi.inTransaction { conn: Handle, _ ->
            val itr = conn.createQuery(
                    """
select artifactId,
       sourceFile,
       array_agg(lexemes) lexemes,
       array_agg(starts) starts,
       array_agg(lengths) lengths,
       (select text, annotations
        from sourceFiles
        where artifactId = sourceFileLexemes.artifactId
          and sourceFile = sourceFileLexemes.sourceFile)
from sourceFileLexemes
where lexemes @@ :query
group by sourceFileLexemes.artifactId, sourceFileLexemes.path
order by max(ts_rank_cd('{1,1,1,1}', sourceFileLexemes.lexemes, :query))
                    """
            )
                    .bind("query", tsQuery.toString())
                    .map { _, r, _ ->
                        FileResult(
                                r.getString("artifactId"),
                                r.getString("sourceFile"),
                                (r.getArray("lexemes").array as Array<*>).map {
                                    val vc = TsVector()
                                    vc.addFromSql(it as String)
                                    vc
                                },
                                r.getArray("starts").array as IntArray,
                                r.getArray("lengths").array as IntArray,
                                ServerSourceFile(
                                        objectMapper,
                                        textBytes = r.getBytes("text"),
                                        annotationBytes = r.getBytes("annotations")
                                )
                        )
                    }
                    .iterator()



        }
    }
}