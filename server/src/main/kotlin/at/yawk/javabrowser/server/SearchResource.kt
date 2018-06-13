package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus
import javax.ws.rs.DefaultValue
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

/**
 * @author yawkat
 */
@Path("/api/search")
class SearchResource(private val dbi: DBI) {
    private val searchIndex = SearchIndex<String, String>()

    fun ready(artifactId: String) {
        dbi.inTransaction { conn: Handle, _ ->
            val itr = conn.createQuery("select binding, sourceFile from bindings where isType and artifactId = ?")
                    .bind(0, artifactId)
                    .map { _, r, _ -> r.getString(1) to r.getString(2) }
                    .iterator()
            searchIndex.replace(artifactId, itr)
        }
    }

    @Path("/{query:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @GET
    fun search(@PathParam("query") query: String,
               @QueryParam("artifactId") artifactId: String?,
               @QueryParam("limit") @DefaultValue("100") limit: Int): Response {
        val f = if (artifactId == null) {
            searchIndex.find(query)
        } else {
            val dependencies = dbi.inTransaction { conn: Handle, _ ->
                conn.attach(DependencyDao::class.java).getDependencies(artifactId)
            }
            searchIndex.find(query, setOf(artifactId) + dependencies)
        }
        return Response(f.take(limit).map {
            val componentLengths = IntArray(it.entry.componentsLower.size) { i -> it.entry.componentsLower[i].length }
            Result(it.key, it.entry.string, it.entry.value, componentLengths, it.match)
        }.asIterable())
    }

    @Suppress("unused")
    class Response(
            @JsonSerialize(typing = JsonSerialize.Typing.STATIC)
            val items: Iterable<Result>
    )

    @Suppress("unused")
    class Result(
            val artifactId: String,
            val binding: String,
            val path: String,
            val components: IntArray,
            val match: IntArray
    )
}