package at.yawk.javabrowser.server

import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import java.util.EnumSet

/**
 * @author yawkat
 */
class FullTextSearchResource(
        private val dbi: DBI
) : HttpHandler {
    companion object {
        const val PATTERN = "/fts/{query}"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = (exchange.queryParameters["query"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Query not given"))
    }

    private sealed class TsQuery {
        abstract override fun toString(): String

        data class Negation(val query: TsQuery) : TsQuery() {
            override fun toString() = "!$query"
        }

        data class Disjunction(val operands: List<TsQuery>) : TsQuery() {
            override fun toString() = operands.joinToString(separator = " & ", prefix = "(", postfix = ")")
        }

        data class Conjunction(val operands: List<TsQuery>) : TsQuery() {
            override fun toString() = operands.joinToString(separator = " | ", prefix = "(", postfix = ")")
        }

        data class Term(val term: String,
                        val weights: Set<Weight> = EnumSet.allOf(Weight::class.java),
                        val matchStart: Boolean = false) : TsQuery() {
            override fun toString(): String {
                val matchAllWeights = weights.size == 4
                var suffix: String
                if (matchAllWeights && !matchStart) {
                    suffix = ""
                } else {
                    suffix = ":"
                    if (!matchAllWeights) {
                        suffix = weights.joinToString(prefix = ":") { it.value.toString() }
                    }
                    if (matchStart) {
                        suffix += "*"
                    }
                }
                // escape the term
                return "'" + term.replace("''", "'") + "'" + suffix
            }
        }

        enum class Weight(val value: Char) { A('A'), B('B'), C('C'), D('D') }
    }
}