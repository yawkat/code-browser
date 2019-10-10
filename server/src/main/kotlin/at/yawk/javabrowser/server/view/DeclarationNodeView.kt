package at.yawk.javabrowser.server.view

/**
 * @author yawkat
 */
data class DeclarationNodeView(
        val children: Iterator<DeclarationNode>,
        val parentBinding: String?,
        val diffArtifactId: String?
) : View("declarationNodeView.ftl")