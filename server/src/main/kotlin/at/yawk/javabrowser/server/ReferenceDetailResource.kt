package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.artifact.ArtifactNode
import at.yawk.javabrowser.server.view.ReferenceDetailView
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import com.google.common.collect.Table
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

/**
 * @author yawkat
 */
class ReferenceDetailResource @Inject constructor(
        @param:LongRunningDbi private val dbi: DBI,
        private val ftl: Ftl,
        private val artifactIndex: ArtifactIndex
) : HttpHandler {
    companion object {
        const val PATTERN = "/references/{realm}/{targetBinding}"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val realmName = exchange.queryParameters["realm"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass realm")
        val realm = Realm.parse(realmName) ?: throw HttpException(404, "Realm not found")
        var targetBinding = exchange.queryParameters["targetBinding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target binding")
        targetBinding = URLDecoder.decode(targetBinding, "UTF-8")
        val type = exchange.queryParameters["type"]?.peekFirst()?.let {
            try {
                BindingRefType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                throw HttpException(400, "Unknown type")
            }
        }
        val sourceArtifact = exchange.queryParameters["fromArtifact"]?.peekFirst()?.let {
            val parsed = artifactIndex.parse(it)
            if (parsed.remainingPath != null) throw HttpException(404, "No such artifact")
            parsed.node
        }
        val limit: Int? = when (val limitString = exchange.queryParameters["limit"]?.peekFirst()) {
            null -> 10000
            "" -> null // no limit!
            else -> {
                try {
                    limitString.toInt()
                } catch (e: NumberFormatException) {
                    throw HttpException(400, "Illegal limit")
                }
            }
        }
        dbi.inTransaction { conn: Handle, _ ->
            // render within the transaction so we can stream results
            ftl.render(exchange, handleRequest(conn, realm, targetBinding, sourceArtifact, type, limit))
        }
    }

    private data class Overview(
            val countTable: Table<String, BindingRefType, Int>,
            val countsByType: Map<BindingRefType, Int>,
            val countsByArtifact: Map<String, Int>,
            val totalCount: Int,

            val bindingId: BindingId
    )

    private fun buildOverview(
            conn: Handle,
            realm: Realm,
            targetBinding: String
    ): Overview {
        val countTable = HashBasedTable.create<String, BindingRefType, Int>(16, BindingRefType.values().size)
        var bindingIdTmp: BindingId? = null
        for (row in conn.select("""
                    select binding_id, type, source_artifact_id, count count from binding_reference_count_view v
                    right join binding on binding.realm = v.realm and binding.binding_id = v.target
                    where v.realm = ? and binding.binding = ?
                """, realm.id, targetBinding)) {
            val bindingId = BindingId((row["binding_id"] as Number).toLong())
            if (bindingIdTmp != null) {
                if (bindingIdTmp != bindingId) {
                    throw AssertionError("Two bindings with different id but same name")
                }
            } else {
                bindingIdTmp = bindingId
            }

            if (row["type"] == null) {
                // this is a right join, so there might be bindings with no references.
                continue
            }

            val type = BindingRefType.byIdOrNull((row["type"] as Number).toInt())
            // can happen if the generator is newer than the frontend. We could return UNCLASSIFIED here, but then the search for UNCLASSIFIED wouldn't match this number.
                    ?: continue
            val sourceArtifactId = (row["source_artifact_id"] as Number).toLong()
            val sourceArtifact =  artifactIndex.allArtifactsByDbId[sourceArtifactId]!!
            countTable.put(sourceArtifact.stringId, type, (row["count"] as Number).toInt())
        }
        if (bindingIdTmp == null) {
            // since this is a right join, this actually means "no binding" and not only "no references to this"
            throw HttpException(404, "No such binding")
        }
        return Overview(
                countTable,
                countsByType = countTable.columnKeySet().associateWith { countTable.column(it).values.sum() },
                countsByArtifact = countTable.rowKeySet().associateWith { countTable.row(it).values.sum() },
                totalCount = countTable.values().sum(),
                bindingId = bindingIdTmp
        )
    }

    @VisibleForTesting
    internal fun handleRequest(conn: Handle,
                              realm: Realm,
                              targetBinding: String,
                              sourceArtifact: ArtifactNode?,
                              type: BindingRefType?,
                              limit: Int?): ReferenceDetailView {
        val overview = buildOverview(conn, realm, targetBinding)

        // maximum result count on a single artifact
        val totalCountInSelection: Int
        if (type != null) {
            if (sourceArtifact != null) {
                totalCountInSelection = overview.countTable.get(sourceArtifact.stringId, type) ?: 0
            } else {
                totalCountInSelection = overview.countsByType[type] ?: 0
            }
        } else {
            if (sourceArtifact != null) {
                totalCountInSelection = overview.countsByArtifact[sourceArtifact.stringId] ?: 0
            } else {
                totalCountInSelection = overview.totalCount
            }
        }

        val hitResultLimit = totalCountInSelection > (limit ?: Int.MAX_VALUE)

        val query = conn.createQuery("""
                    select type, source_artifact_id, source_file.path, source_file_line, source_file_ref_id from binding_reference
                    inner join source_file on source_file.realm = binding_reference.realm and source_file.artifact_id = binding_reference.source_artifact_id and source_file.source_file_id = binding_reference.source_file_id
                    where binding_reference.realm = :realm 
                    and binding_reference.target = :targetBinding
                    ${if (type != null) "and binding_reference.type = :type" else ""}
                    ${if (sourceArtifact != null) "and binding_reference.source_artifact_id = :sourceArtifactId" else ""}
                    order by type, source_artifact_id, source_file.path, source_file_ref_id
                    ${if (hitResultLimit) "limit :limit" else ""}
                """).map { _, r, _ ->
            Row(
                    type = BindingRefType.byIdOrNull(r.getInt(1)),
                    sourceArtifactId = r.getLong(2),
                    sourceFile = r.getString(3),
                    sourceFileLine = r.getInt(4),
                    sourceFileRefId = r.getInt(5)
            )
        }
        query.bind("realm", realm.id)
        query.bind("targetBinding", overview.bindingId.hash)
        if (type != null) query.bind("type", type.id)
        if (sourceArtifact != null) query.bind("sourceArtifactId", sourceArtifact.dbId)
        if (hitResultLimit) query.bind("limit", limit)
        query.setFetchSize(500)
        return ReferenceDetailView(
                realm = realm,
                targetBinding = targetBinding,
                baseUri = URI("/references/$realm/${URLEncoder.encode(targetBinding, "UTF-8")}"),
                type = type,
                sourceArtifactId = sourceArtifact,

                countTable = overview.countTable,
                countsByType = overview.countsByType,
                countsByArtifact = overview.countsByArtifact,
                totalCount = overview.totalCount,

                resultLimit = limit,
                hitResultLimit = hitResultLimit,
                totalCountInSelection = totalCountInSelection,

                results = TypeIterator(query.iterator())
        )
    }

    /**
     * Top-level iterator. Splits an iterator of rows into sections for each type.
     */
    private inner class TypeIterator(flatDelegate: Iterator<Row>) :
            TreeIterator<Row, ReferenceDetailView.TypeListing?>(Iterators.peekingIterator(flatDelegate)) {
        override fun mapOneItem(): ReferenceDetailView.TypeListing? {
            val top = flatDelegate.peek()
            if (top.type == null) return null
            val artifacts = ArtifactIterator(top.type, flatDelegate)
            registerSubIterator(artifacts)
            return ReferenceDetailView.TypeListing(top.type, artifacts)
        }

        override fun returnToParent(item: Row): Boolean {
            return false
        }
    }

    /**
     * Splits an iterator of rows of the same type into sections for each artifact.
     */
    private inner class ArtifactIterator(
            val currentType: BindingRefType,

            flatDelegate: PeekingIterator<Row>
    ) : TreeIterator<Row, ReferenceDetailView.ArtifactListing?>(flatDelegate) {
        override fun mapOneItem(): ReferenceDetailView.ArtifactListing? {
            val top = flatDelegate.peek()
            val artifactNode = artifactIndex.allArtifactsByDbId[top.sourceArtifactId] ?: return null
            val sourceFiles = SourceFileIterator(artifactNode, flatDelegate)
            registerSubIterator(sourceFiles)
            return ReferenceDetailView.ArtifactListing(artifactNode.stringId, sourceFiles)
        }

        override fun returnToParent(item: Row): Boolean {
            return item.type != currentType
        }

        /**
         * Splits an iterator of rows of the same artifact into sections for each source file.
         */
        private inner class SourceFileIterator(
                private val currentArtifact: ArtifactNode,

                flatDelegate: PeekingIterator<Row>
        ) : TreeIterator<Row, ReferenceDetailView.SourceFileListing>(flatDelegate) {
            override fun mapOneItem(): ReferenceDetailView.SourceFileListing {
                val sourceFile = flatDelegate.peek().sourceFile
                val items = ArrayList<ReferenceDetailView.ReferenceListing>()
                while (flatDelegate.hasNext() &&
                        !returnToParent(flatDelegate.peek()) &&
                        flatDelegate.peek().sourceFile == sourceFile) {
                    val row = flatDelegate.next()
                    items.add(ReferenceDetailView.ReferenceListing(
                            sourceArtifactStringId = currentArtifact.stringId,
                            sourceFile = row.sourceFile,
                            sourceFileLine = row.sourceFileLine,
                            sourceFileRefId = row.sourceFileRefId
                    ))
                }
                return ReferenceDetailView.SourceFileListing(sourceFile, items)
            }

            override fun returnToParent(item: Row): Boolean {
                return this@ArtifactIterator.returnToParent(item) || item.sourceArtifactId != currentArtifact.dbId
            }
        }
    }

    private data class Row(
            val type: BindingRefType?,
            val sourceArtifactId: Long,
            val sourceFile: String,
            val sourceFileLine: Int,
            val sourceFileRefId: Int
    )
}