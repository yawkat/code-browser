package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.IndexView
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

/**
 * @author yawkat
 */
@Path("/")
class RootResource(private val artifactIds: List<String>) {
    @GET
    @Produces(value = [MediaType.TEXT_HTML])
    fun index(): IndexView {
        return IndexView(artifactIds)
    }
}
