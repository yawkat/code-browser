package at.yawk.javabrowser.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.ImmutableList
import com.google.common.net.MediaType
import com.google.common.primitives.Primitives
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.StatementContext
import org.skife.jdbi.v2.TransactionStatus
import org.skife.jdbi.v2.sqlobject.Bind
import org.skife.jdbi.v2.sqlobject.SqlQuery
import org.skife.jdbi.v2.sqlobject.customizers.RegisterMapper
import org.skife.jdbi.v2.tweak.ResultSetMapper
import java.net.URI
import java.sql.ResultSet

/**
 * @author yawkat
 */
class JavabotSearchResource(private val dbi: DBI,
                            private val objectMapper: ObjectMapper) : HttpHandler {
    companion object {
        const val PATTERN = "/api/javabotSearch/v1"

        private fun transformMemberName(memberName: String): String {
            // check if the user was obliging enough to already use the # syntax
            if (memberName.contains('#')) return memberName

            val dotIndex = memberName.indexOf('.')
            return memberName.substring(0, dotIndex) + "#" + memberName.substring(dotIndex + 1)
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val term = exchange.queryParameters["term"]?.peekFirst()
                ?: throw HttpException(StatusCodes.BAD_REQUEST, "Term not given")
        val artifact = exchange.queryParameters["artifact"]?.peekFirst()

        val rows = dbi.inTransaction { conn: Handle, _: TransactionStatus ->
            handleRequest(conn.attach(Dao::class.java), term, artifact)
        }
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
        objectMapper.writeValue(exchange.outputStream, rows)
    }

    private fun handleRequest(dao: Dao, term: String, artifact: String?): List<ResultRow> {
        val allArtifacts = dao.artifacts()
        val artifactPattern = when {
            // no artifact given
            artifact == null -> "%"
            // exact name
            allArtifacts.any { it.equals(artifact, ignoreCase = true) } -> artifact
            // name without version
            allArtifacts.any { it.startsWith("$artifact/", ignoreCase = true) } -> "$artifact/%"
            // artifact name without group id
            allArtifacts.any { it.contains("/$artifact/", ignoreCase = true) } -> "%/$artifact/%"
            // no such artifact
            else -> return emptyList()
        }

        var result: Iterator<ResultRow>
        val parenthesesIndex = term.indexOf('(')
        if (parenthesesIndex != -1) {
            // method search.
            var namePart = transformMemberName(term.substring(0, parenthesesIndex))
            if (!namePart.contains('.')) {
                namePart = "%.$namePart"
            }
            val parameters = term.substring(parenthesesIndex + 1).removeSuffix(")").split(',')
                    .map { it.trim() }
                    .map {
                        when {
                            it == "*" -> "%"
                            it.contains('.') -> it
                            Primitives.allPrimitiveTypes().any { pt -> pt.name.equals(it, ignoreCase = true) } -> it
                            else -> "%.$it"
                        }
                    }
            val fullPattern = "$namePart(${parameters.joinToString(",")})"
            // this pattern might not contain a wildcard at all, but postgres is smart enough to use an index scan
            // in that case.
            result = dao.nonClassPattern(artifactPattern, fullPattern)
        } else {
            val dotIndex = term.indexOf('.')
            if (dotIndex != -1) {
                // either field search or fully qualified class name.

                // check qualified name first
                result = dao.qualified(artifactPattern, term)
                if (!result.hasNext()) {
                    val fieldModified = transformMemberName(term)
                    // maybe it's a fully qualified field name?
                    result = dao.qualified(artifactPattern, fieldModified)
                    if (!result.hasNext()) {
                        // only a pattern match remains
                        result = dao.nonClassPattern(artifactPattern, "%.$fieldModified")
                    }
                }
            } else {
                // unqualified class name
                result = dao.topLevelClassPattern(artifactPattern, "%.$term")
            }
        }

        var items: List<ResultRow> = ImmutableList.copyOf(result)
        // remove items where a newer version exists
        items = items.filter { old ->
            items.none { new ->
                old.binding == new.binding && VersionComparator.compare(old.artifactId, new.artifactId) > 0
            }
        }

        return items
    }

    @RegisterMapper(ResultRowMapper::class)
    private interface Dao {
        companion object {
            private const val INTEREST_COLUMNS = "artifactId, binding, sourceFile"

            private const val SUFFIX = "limit 20"
        }

        @SqlQuery("select id from data.artifacts")
        fun artifacts(): List<String>

        @SqlQuery("select $INTEREST_COLUMNS from data.bindings where artifactId ilike :artifactPattern and binding ilike :fieldNamePattern $SUFFIX")
        fun nonClassPattern(@Bind("artifactPattern") artifactPattern: String, @Bind("fieldNamePattern") fieldNamePattern: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from data.bindings where artifactId ilike :artifactPattern and binding = :qualifiedName $SUFFIX")
        fun qualified(@Bind("artifactPattern") artifactPattern: String, @Bind("qualifiedName") qualifiedName: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from data.bindings where artifactId ilike :artifactPattern and isType and parent is null and binding ilike :classPattern $SUFFIX")
        fun topLevelClassPattern(@Bind("artifactPattern") artifactPattern: String, @Bind("classPattern") classPattern: String): Iterator<ResultRow>
    }

    class ResultRowMapper : ResultSetMapper<ResultRow> {
        override fun map(index: Int, r: ResultSet, ctx: StatementContext) = ResultRow(
                r.getString(1),
                r.getString(2),
                r.getString(3)
        )
    }

    class ResultRow(
            val artifactId: String,
            val binding: String,
            @JsonIgnore val sourceFile: String
    ) {
        val uri: URI
            get() = BindingResolver.location(artifactId, sourceFile, BindingResolver.bindingHash(binding))
    }
}