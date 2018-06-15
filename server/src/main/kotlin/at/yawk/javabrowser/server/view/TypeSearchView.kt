package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.ArtifactId
import io.dropwizard.views.View
import java.net.URI

/**
 * @author yawkat
 */
@Suppress("unused")
class TypeSearchView(val artifactId: ArtifactId,
                     val dependencies: List<Dependency>) : View("type-search.ftl") {
    data class Dependency(
            val prefix: String?,
            val suffix: String
    )
}
