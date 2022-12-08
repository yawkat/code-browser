package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.common.net.MediaType
import com.google.common.primitives.Primitives
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.RowMapper
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.sqlobject.config.RegisterRowMapper
import org.jdbi.v3.sqlobject.customizer.Bind
import org.jdbi.v3.sqlobject.statement.SqlQuery
import java.net.URI
import java.sql.ResultSet
import java.util.Objects
import javax.inject.Inject

/**
 * @author yawkat
 */
class JavabotSearchResource @Inject constructor(
        private val dbi: Jdbi,
        private val objectMapper: ObjectMapper,
        private val artifactIndex: ArtifactIndex
) : HttpHandler {
    companion object {
        const val PATTERN = "/api/javabotSearch/v1"

        private fun transformMemberName(memberName: String): String {
            // check if the user was obliging enough to already use the # syntax
            if (memberName.contains('#')) return memberName

            val dotIndex = memberName.lastIndexOf('.')
            return memberName.substring(0, dotIndex) + "#" + memberName.substring(dotIndex + 1)
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val term = exchange.queryParameters["term"]?.peekFirst()
                ?: throw HttpException(StatusCodes.BAD_REQUEST, "Term not given")
        val artifact = exchange.queryParameters["artifact"]?.peekFirst()

        val rows = handleRequest(term, artifact)
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
        objectMapper.writeValue(exchange.outputStream, rows)
    }

    @VisibleForTesting
    internal fun handleRequest(term: String, artifact: String?): List<ResultRow> {
        return dbi.inTransaction<List<ResultRow>, Exception> { conn: Handle ->
            handleRequest(conn.attach(Dao::class.java), term, artifact)
        }
    }

    private fun handleRequest(dao: Dao, term: String, artifact: String?): List<ResultRow> {
        var selectedArtifacts: List<ArtifactNode>? = null
        if (artifact != null) {
            val leafArtifacts = artifactIndex.leafArtifacts
            // exact name
            selectedArtifacts = leafArtifacts.filter { it.stringId.equals(artifact, ignoreCase = true) }
            if (selectedArtifacts.isEmpty()) {
                // name without version
                selectedArtifacts = leafArtifacts.filter { it.stringId.startsWith("$artifact/", ignoreCase = true) }
                if (selectedArtifacts.isEmpty()) {
                    // artifact name without group id
                    selectedArtifacts = leafArtifacts.filter { it.stringId.contains("/$artifact/", ignoreCase = true) }
                }
            }
        }
        val selectedArtifactIds = selectedArtifacts?.let { LongArray(it.size) { i -> it[i].dbId!! } }

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
            result = dao.nonClassPattern(selectedArtifactIds, fullPattern)
        } else {
            val dotIndex = term.indexOf('.')
            if (dotIndex != -1) {
                // either field search or fully qualified class name.

                // check qualified name first
                result = dao.qualified(selectedArtifactIds, term)
                if (!result.hasNext()) {
                    val fieldModified = transformMemberName(term)
                    // maybe it's a fully qualified field name?
                    result = dao.qualified(selectedArtifactIds, fieldModified)
                    if (!result.hasNext()) {
                        // only a pattern match remains
                        result = dao.nonClassPattern(selectedArtifactIds, "%.$fieldModified")
                    }
                }
            } else {
                // unqualified class name
                result = dao.topLevelClassPattern(selectedArtifactIds, "%.$term")
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

    // versions with nullable artifact array. null means all artifacts

    private fun Dao.nonClassPattern(artifacts: LongArray?, fieldNamePattern: String): Iterator<ResultRow> =
            if (artifacts == null) nonClassPattern(fieldNamePattern)
            else nonClassPattern(artifacts, fieldNamePattern)

    private fun Dao.qualified(artifacts: LongArray?, qualifiedName: String): Iterator<ResultRow> =
            if (artifacts == null) qualified(qualifiedName)
            else qualified(artifacts, qualifiedName)

    private fun Dao.topLevelClassPattern(artifacts: LongArray?, classPattern: String): Iterator<ResultRow> =
            if (artifacts == null) topLevelClassPattern(classPattern)
            else topLevelClassPattern(artifacts, classPattern)

    @RegisterRowMapper(ResultRowMapper::class)
    private interface Dao {
        companion object {
            private const val INTEREST_COLUMNS = "artifact.string_id, binding.binding, source_file.path"

            private const val SUFFIX = "limit 20"
        }

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact natural join source_file where realm = 0 and artifact_id = any(:artifacts) and binding ilike :fieldNamePattern $SUFFIX")
        fun nonClassPattern(@Bind("artifacts") artifacts: LongArray,
                            @Bind("fieldNamePattern") fieldNamePattern: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact natural join source_file where realm = 0 and binding ilike :fieldNamePattern $SUFFIX")
        fun nonClassPattern(@Bind("fieldNamePattern") fieldNamePattern: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact where realm = 0 and artifact_id = any(:artifacts) and binding = :qualifiedName $SUFFIX")
        fun qualified(@Bind("artifacts") artifacts: LongArray,
                      @Bind("qualifiedName") qualifiedName: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact natural join source_file where realm = 0 and binding = :qualifiedName $SUFFIX")
        fun qualified(@Bind("qualifiedName") qualifiedName: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact natural join source_file where realm = 0 and artifact_id = any(:artifacts) and include_in_type_search and binding ilike :classPattern $SUFFIX")
        fun topLevelClassPattern(@Bind("artifacts") artifacts: LongArray,
                                 @Bind("classPattern") classPattern: String): Iterator<ResultRow>

        @SqlQuery("select $INTEREST_COLUMNS from binding natural join artifact natural join source_file where realm = 0 and include_in_type_search and binding ilike :classPattern $SUFFIX")
        fun topLevelClassPattern(@Bind("classPattern") classPattern: String): Iterator<ResultRow>
    }

    class ResultRowMapper : RowMapper<ResultRow> {
        override fun map(r: ResultSet, ctx: StatementContext) = ResultRow(
                r.getString(1),
                r.getString(2),
                r.getString(3)
        )
    }

    data class ResultRow(
            val artifactId: String,
            val binding: String,
            @JsonIgnore val sourceFile: String
    ) {
        val uri: URI
            get() = Locations.location(artifactId, sourceFile, Locations.bindingHash(binding))

        override fun equals(other: Any?) =
                other is ResultRow && this.artifactId == other.artifactId && this.binding == other.binding && this.sourceFile == other.sourceFile

        override fun hashCode() = Objects.hash(artifactId, binding, sourceFile)
    }
}