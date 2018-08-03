package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.artifact.ArtifactPath

/**
 * @author yawkat
 */
@Suppress("unused")
data class TypeSearchView(val artifactId: ArtifactPath,
                          val dependencies: List<Dependency>) : View("type-search.ftl") {
    data class Dependency(
            val prefix: String?,
            val suffix: String
    )
}
