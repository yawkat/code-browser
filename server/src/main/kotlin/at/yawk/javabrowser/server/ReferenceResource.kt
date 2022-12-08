package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import com.google.common.net.MediaType
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.net.URLDecoder
import javax.inject.Inject

/**
 * @author yawkat
 */
class ReferenceResource @Inject constructor(
        private val dbi: Jdbi,
        private val objectMapper: ObjectMapper,
        private val artifactIndex: ArtifactIndex
) : HttpHandler {
    companion object {
        const val PATTERN = "/api/references/{realm}/{targetBinding}"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val realmName = exchange.queryParameters["realm"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass realm")
        val realm = Realm.parse(realmName) ?: throw HttpException(404, "Realm not found")
        var targetBinding = exchange.queryParameters["targetBinding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target binding")
        targetBinding = URLDecoder.decode(targetBinding, "UTF-8")
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100

        val targetArtifactId = exchange.queryParameters["targetArtifactId"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target artifact")
        val targetArtifact = artifactIndex.allArtifactsByStringId[targetArtifactId]
                ?: throw HttpException(404, "No such artifact")

        dbi.inTransaction<Unit, Exception> { conn ->
            val typeResults = handleRequest(conn, realm, targetBinding, limit, targetArtifact)

            exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())

            val itemWriter = objectMapper.writerFor(ResponseItem::class.java)

            val gen = objectMapper.factory.createGenerator(exchange.outputStream)
            gen.writeStartObject()
            for (typeResult in typeResults) {
                if (typeResult == null) continue
                gen.writeFieldName(typeResult.type.displayName)
                gen.writeStartArray()
                for (item in typeResult.items) {
                    itemWriter.writeValue(gen, item)
                }
                gen.writeEndArray()
            }
            gen.writeEndObject()
            gen.close()
        }
    }

    @VisibleForTesting
    internal fun handleRequest(
            conn: Handle,

            realm: Realm,
            targetBinding: String,
            limit: Int,
            targetArtifact: ArtifactNode
    ): Iterator<TypeResult?> {
        val query = conn.createQuery("""
select type, source_artifact_id, path, source_file_line, source_file_ref_id
from (
    -- first, collect some information on the different binding ref types.
      select target selected_target, type selected_type,
             sum(case when source_artifact_id = :targetArtifactId then count else 0 end) count_here
      from binding_reference_count_view brcv
      where target = (select binding_id from binding where realm = :realm and artifact_id = :targetArtifactId and binding.binding = :targetBinding)
      group by target, type
  ) types,
  -- then, for each type, collect the references.
  lateral (
       -- this CTE contains preliminary filtering by realm and target
        with candidates as not materialized (
            select *
                from binding_reference ref
                         inner join source_file sf on sf.realm = 0 and sf.artifact_id = source_artifact_id and
                                                      sf.source_file_id = ref.source_file_id
                where ref.realm = :realm
                  and ref.target = selected_target
                  and type = selected_type
        )
      -- references from the same artifact
      (select * from candidates where source_artifact_id = :targetArtifactId limit least(count_here, :limit))
      union all
      -- references from other artifacts
      (select * from candidates where source_artifact_id != all (:excludeArtifactIds) limit greatest(0, :limit - count_here))
  ) _
order by type, source_artifact_id != :targetArtifactId, source_artifact_id, path, source_file_ref_id
            """)
                .bind("realm", realm.id)
                .bind("targetBinding", targetBinding)
                .bind("limit", limit)
                .bind("targetArtifactId", targetArtifact.dbId)
                .bind("excludeArtifactIds", targetArtifact.parent!!.children.mapNotNull { it.value.dbId }.toLongArray())
                .setFetchSize(1000)
        return RootIterator(query.map { r, _, _ ->
            Row(
                    type = BindingRefType.byIdOrNull(r.getInt(1)),
                    artifactId = r.getLong(2),
                    sourceFilePath = r.getString(3),
                    line = r.getInt(4),
                    sourceFileRefId = r.getInt(5)
            )
        }.iterator())
    }

    private inner class RootIterator(flatDelegate: Iterator<Row>) :
            TreeIterator<Row, TypeResult?>(Iterators.peekingIterator(flatDelegate)) {
        override fun mapOneItem(): TypeResult? {
            val next = flatDelegate.peek()
            if (next.type == null) return null
            val iteratorForType = RefTypeIterator(next.type, flatDelegate)
            registerSubIterator(iteratorForType)
            return TypeResult(next.type, iteratorForType)
        }

        override fun returnToParent(item: Row): Boolean {
            return false
        }
    }

    private inner class RefTypeIterator(
            val currentType: BindingRefType,
            flatDelegate: PeekingIterator<Row>
    ) : TreeIterator<Row, ResponseItem>(flatDelegate) {
        override fun mapOneItem(): ResponseItem {
            val here = flatDelegate.next()
            return ResponseItem(
                    artifactId = artifactIndex.allArtifactsByDbId[here.artifactId]!!.stringId,
                    sourceFile = here.sourceFilePath,
                    line = here.line,
                    sourceFileId = here.sourceFileRefId
            )
        }

        override fun returnToParent(item: Row): Boolean {
            return item.type != currentType
        }
    }

    @VisibleForTesting
    internal class TypeResult(
            val type: BindingRefType,
            val items: Iterator<ResponseItem>
    )

    @VisibleForTesting
    internal class ResponseItem(
            val artifactId: String,
            val sourceFile: String,
            val line: Int,
            sourceFileId: Int
    ) {
        val uri = Locations.location(artifactId, sourceFile, "#ref-$sourceFileId")

        override fun toString() = "ResponseItem($uri, line=$line)"
    }

    private data class Row(
            val type: BindingRefType?,
            val artifactId: Long,
            val sourceFilePath: String,
            val line: Int,
            val sourceFileRefId: Int
    )
}