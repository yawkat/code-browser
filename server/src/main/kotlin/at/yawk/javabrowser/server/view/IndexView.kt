package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.artifact.ArtifactNode

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(
        val artifactId: ArtifactNode,
        val siteStatistics: SiteStatistics
) : View("index.ftl")
