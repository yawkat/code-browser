package at.yawk.javabrowser.server.view

import io.dropwizard.views.View

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(
        val base: String?,
        val prefix: String,
        val versions: List<String>,
        val children: List<String>
) : View("index.ftl")
