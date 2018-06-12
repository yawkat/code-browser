package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingRefType
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
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
@Path("/api/references")
class ReferenceResource(private val dbi: DBI) {
    @Path("/{targetBinding:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @GET
    fun search(@PathParam("targetBinding") targetBinding: String,
               @QueryParam("limit") @DefaultValue("100") limit: Int): Map<String, List<ResponseItem>> =
            dbi.inTransaction { conn: Handle, _ ->
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