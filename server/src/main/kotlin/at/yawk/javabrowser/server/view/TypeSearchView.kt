package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.ArtifactId
import io.dropwizard.views.View

/**
 * @author yawkat
 */
@Suppress("unused")
class TypeSearchView(val artifactId: ArtifactId) : View("type-search.ftl")
