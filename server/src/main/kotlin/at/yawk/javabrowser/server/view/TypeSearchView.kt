package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.server.artifact.ArtifactNode

/**
 * @author yawkat
 */
@Suppress("unused")
data class TypeSearchView(val artifactId: ArtifactNode,
                          val artifactMetadata: ArtifactMetadata,
                          val dependencies: List<Dependency>,
                          val topLevelPackages: Iterator<PackageNode>) : View("type-search.ftl") {
    data class Dependency(
            val prefix: String?,
            val suffix: String
    )
}
