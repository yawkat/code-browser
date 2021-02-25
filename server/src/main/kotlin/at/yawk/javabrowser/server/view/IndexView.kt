package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.ParsedPath

/**
 * @author yawkat
 */
@Suppress("unused")
class IndexView(
        val path: ParsedPath,
        val siteStatistics: SiteStatistics
) : View("index.ftl")
