package at.yawk.javabrowser.server.view

/**
 * @author yawkat
 */
data class PackageNodeView(val children: Iterator<Any>,
                           val fullSourceFilePath: String? = null) : View("packageNodeView.ftl")