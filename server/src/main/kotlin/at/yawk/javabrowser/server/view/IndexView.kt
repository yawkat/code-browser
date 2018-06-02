package at.yawk.javabrowser.server.view

import io.dropwizard.views.View

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(val artifacts: List<String>) : View("index.ftl")
