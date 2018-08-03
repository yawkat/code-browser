package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.artifact.ArtifactPath

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(
        val artifactId: ArtifactPath
) : View("index.ftl")
