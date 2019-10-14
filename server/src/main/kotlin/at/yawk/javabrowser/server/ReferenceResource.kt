package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.net.MediaType
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle

/**
 * @author yawkat
 */
class ReferenceResource(private val dbi: DBI, private val objectMapper: ObjectMapper) : HttpHandler {
    companion object {
        const val PATTERN = "/api/references/{targetBinding}"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val targetBinding = exchange.queryParameters["targetBinding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass target binding")
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100
        val data = dbi.inTransaction { conn: Handle, _ ->
            BindingRefType.values().associate { type ->
                type.name to conn.createQuery("select sourceArtifactId, sourceFile, sourceFileLine, sourceFileId from binding_references where targetBinding = ? and type = ? limit ?")
                        .bind(0, targetBinding)
                        .bind(1, type.id)
                        .bind(2, limit)
                        .map { _, r, _ ->
                            ResponseItem(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4))
                        }.list()
            }
        }

        exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
        objectMapper.writeValue(exchange.outputStream, data)
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate", "CanBeParameter")
    class ResponseItem(
            val artifactId: String,
            val sourceFile: String,
            val line: Int,
            sourceFileId: Int
    ) {
        val uri = BindingResolver.location(artifactId, sourceFile, "#ref-$sourceFileId")
    }
}