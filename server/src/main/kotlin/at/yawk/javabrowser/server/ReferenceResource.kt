package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.net.MediaType
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.StatementContext
import org.skife.jdbi.v2.tweak.ResultSetMapper
import java.sql.ResultSet
import javax.inject.Inject

/**
 * @author yawkat
 */
class ReferenceResource @Inject constructor(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper
) : HttpHandler {
    companion object {
        const val PATTERN = "/api/references/{targetBinding}"

        private const val SELECT_EXPR =
                "select sourceArtifactId, sourceFile, sourceFileLine, sourceFileId from binding_references"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val targetBinding = exchange.queryParameters["targetBinding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target binding")
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100

        // if targetArtifactId is given, exclude any results from other versions
        val targetArtifactId = exchange.queryParameters["targetArtifactId"]?.peekFirst()
        val excludePrefix = targetArtifactId?.let { it.substring(0, it.lastIndexOf('/') + 1) + '%' }

        dbi.inTransaction { conn: Handle, _ ->
            exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())

            val gen = objectMapper.factory.createGenerator(exchange.outputStream)
            gen.writeStartObject()

            val itemWriter = objectMapper.writerFor(ResponseItem::class.java)

            for (type in BindingRefType.values()) {
                gen.writeFieldName(type.name)
                gen.writeStartArray()
                if (targetArtifactId != null) {
                    // first, items from the same artifact
                    val sameArtifact = conn.createQuery("$SELECT_EXPR where targetBinding = ? and type = ? and sourceArtifactId = ? limit ?")
                            .bind(0, targetBinding)
                            .bind(1, type.id)
                            .bind(2, targetArtifactId)
                            .bind(3, limit)
                            .map(ResponseItemMapper)
                    var alreadyReturned = 0
                    for (item in sameArtifact) {
                        itemWriter.writeValue(gen, item)
                        alreadyReturned++
                    }
                    // then, fill up the remainder with usages from other artifacts
                    if (alreadyReturned < limit) {
                        conn.createQuery("$SELECT_EXPR where targetBinding = ? and type = ? and sourceArtifactId not like ? limit ?")
                                .bind(0, targetBinding)
                                .bind(1, type.id)
                                .bind(2, excludePrefix)
                                .bind(3, limit - alreadyReturned)
                                .map(ResponseItemMapper)
                                .forEach { itemWriter.writeValue(gen, it) }
                    }
                } else {
                    conn.createQuery("$SELECT_EXPR where targetBinding = ? and type = ? limit ?")
                            .bind(0, targetBinding)
                            .bind(1, type.id)
                            .bind(2, limit)
                            .map(ResponseItemMapper)
                            .forEach { itemWriter.writeValue(gen, it) }
                }
                gen.writeEndArray()
            }

            gen.writeEndObject()

            gen.close()
        }
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate", "CanBeParameter")
    private class ResponseItem(
            val artifactId: String,
            val sourceFile: String,
            val line: Int,
            sourceFileId: Int
    ) {
        val uri = BindingResolver.location(artifactId, sourceFile, "#ref-$sourceFileId")
    }

    private object ResponseItemMapper : ResultSetMapper<ResponseItem> {
        override fun map(index: Int, r: ResultSet, ctx: StatementContext) =
                ResponseItem(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4))
    }
}