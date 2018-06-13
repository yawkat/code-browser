package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.ArtifactId
import io.dropwizard.views.View

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(
        val artifactId: ArtifactId,
        val prefix: String,
        val versions: List<String>,
        val children: List<String>
) : View("index.ftl")
