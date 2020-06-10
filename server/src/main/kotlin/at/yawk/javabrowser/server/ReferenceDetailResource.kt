package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.ReferenceDetailView
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Iterators
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject

/**
 * @author yawkat
 */
class ReferenceDetailResource @Inject constructor(
        private val dbi: DBI,
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
        val targetBinding = exchange.queryParameters["targetBinding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target binding")
        val type = exchange.queryParameters["type"]?.peekFirst()?.let {
            try {
                BindingRefType.valueOf(it)
            } catch (e: IllegalArgumentException) {
                throw HttpException(400, "Unknown type")
            }
        }
        val sourceArtifactId = exchange.queryParameters["fromArtifact"]?.peekFirst()?.let {
            val parsed = artifactIndex.parse(it)
            if (parsed.remainingPath != null) throw HttpException(404, "No such artifact")
            parsed.node
        }
        val limitString = exchange.queryParameters["limit"]?.peekFirst()
        val limit: Int?
        if (limitString == null) {
            limit = 10000
        } else if (limitString == "") {
            limit = null // no limit!
        } else {
            try {
                limit = limitString.toInt()
            } catch (e: NumberFormatException) {
                throw HttpException(400, "Illegal limit")
            }
        }
        dbi.inTransaction { conn: Handle, _ ->
            val countTable = HashBasedTable.create<String, BindingRefType, Int>(16, BindingRefType.values().size)
            for (row in conn.select("""
                select type, sourceArtifactId, count from binding_references_count_view
                where targetBinding = ?
            """, targetBinding)) {
                val type = BindingRefType.byIdOrNull((row["type"] as Number).toInt())
                // can happen if the generator is newer than the frontend. We could return UNCLASSIFIED here, but then the search for UNCLASSIFIED wouldn't match this number.
                        ?: continue
                countTable.put(row["sourceArtifactId"] as String, type, (row["count"] as Number).toInt())
            }
            val countsByType = countTable.columnKeySet().associateWith { countTable.column(it).values.sum() }
            val countsByArtifact = countTable.rowKeySet().associateWith { countTable.row(it).values.sum() }

            val totalCount = countsByType.values.sum()

            // maximum result count on a single artifact
            val totalCountInSelection: Int
            if (type != null) {
                if (sourceArtifactId != null) {
                    totalCountInSelection = countTable.get(sourceArtifactId.id, type) ?: 0
                } else {
                    totalCountInSelection = countsByType[type] ?: 0
                }
            } else {
                if (sourceArtifactId != null) {
                    totalCountInSelection = countsByArtifact[sourceArtifactId.id] ?: 0
                } else {
                    totalCountInSelection = totalCount
                }
            }

            val hitResultLimit = totalCountInSelection > (limit ?: Int.MAX_VALUE)

            val query = conn.createQuery("""
                select type, sourceArtifactId, sourceFile, sourceFileLine, sourceFileId from binding_references
                                where realm = :realm 
                                and targetBinding = :targetBinding
                                ${if (type != null) "and type = :type" else ""}
                                ${if (sourceArtifactId != null) "and sourceArtifactId = :sourceArtifactId" else ""}
                                order by type, sourceArtifactId, sourceFile, sourceFileId
                                ${if (hitResultLimit) "limit :limit" else ""}
            """).map { _, r, _ ->
                ReferenceDetailView.Row(
                        type = BindingRefType.byIdOrNull(r.getInt(1)),
                        sourceArtifactId = r.getString(2),
                        sourceFile = r.getString(3),
                        sourceFileLine = r.getInt(4),
                        sourceFileId = r.getInt(5)
                )
            }
            query.bind("realm", realm.id)
            query.bind("targetBinding", targetBinding)
            if (type != null) query.bind("type", type.id)
            if (sourceArtifactId != null) query.bind("sourceArtifactId", sourceArtifactId.id)
            if (hitResultLimit) query.bind("limit", limit)
            query.setFetchSize(500)
            val view = ReferenceDetailView(
                    realm = realm,
                    targetBinding = targetBinding,
                    baseUri = URI("/references/${URLEncoder.encode(targetBinding, "UTF-8")}"),
                    type = type,
                    sourceArtifactId = sourceArtifactId,

                    countTable = countTable,
                    countsByType = countsByType,
                    countsByArtifact = countsByArtifact,
                    totalCount = totalCount,

                    resultLimit = limit,
                    hitResultLimit = hitResultLimit,
                    totalCountInSelection = totalCountInSelection,

                    results = ResultGenerator(query.iterator()).splitByType()
            )
            // render within the transaction so we can stream results
            ftl.render(exchange, view)
        }
    }

    /**
     * This is a special iterator that uses a sentinel element and *never* calls delegate.hasNext() outside its own
     * next() call. The purpose of this is this: If you split up an Iterator<T> into an Iterator<Iterator<T>> by some
     * criteria, and do not wish to buffer results, odd things can happen. For example, you could call
     * `inner = outer.next`, then `outer.hasNext` and finally `inner.next`, which would upset the order.
     */
    private class SentinelIterator<T : Any?>(private val delegate: Iterator<T>, private val sentinel: T) : Iterator<T> {
        private var hitEnd = false

        override fun hasNext(): Boolean {
            return !hitEnd
        }

        override fun next(): T {
            if (hitEnd) throw NoSuchElementException()
            if (delegate.hasNext()) return delegate.next()
            else {
                hitEnd = true
                return sentinel
            }
        }
    }

    private class ResultGenerator(resultIterator: Iterator<ReferenceDetailView.Row>) {
        private val iterator = Iterators.peekingIterator(resultIterator)

        fun splitByType() = SentinelIterator(iterator {
            while (iterator.hasNext()) {
                val type = iterator.peek().type ?: continue
                yield(ReferenceDetailView.TypeListing(type, splitByArtifact(type)))
            }
        }, null)

        private fun splitByArtifact(type: BindingRefType) = SentinelIterator(iterator {
            while (iterator.hasNext() && iterator.peek().type == type) {
                val sourceArtifactId = iterator.peek().sourceArtifactId
                yield(ReferenceDetailView.ArtifactListing(
                        sourceArtifactId,
                        splitBySourceFile(sourceArtifactId, type)
                ))
            }
        }, null)

        private fun splitBySourceFile(sourceArtifactId: String, type: BindingRefType) = SentinelIterator(iterator {
            while (iterator.hasNext() &&
                    iterator.peek().sourceArtifactId == sourceArtifactId &&
                    iterator.peek().type == type) {
                val sourceFile = iterator.peek().sourceFile
                val items = ArrayList<ReferenceDetailView.Row>()
                while (iterator.hasNext() &&
                        iterator.peek().sourceArtifactId == sourceArtifactId &&
                        iterator.peek().type == type &&
                        iterator.peek().sourceFile == sourceFile) {
                    items.add(iterator.next())
                }
                yield(ReferenceDetailView.SourceFileListing(sourceFile, items))
            }
        }, null)
    }
}