package at.yawk.javabrowser.server.view

/**
 * @author yawkat
 */
data class PackageNodeView(
        val children: Iterator<DeclarationNode>
) : View("packageNodeView.ftl")