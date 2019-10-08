package at.yawk.javabrowser.server.view

/**
 * @author yawkat
 */
data class DeclarationNodeView(
        val children: Iterator<DeclarationNode>,
        val diffArtifactId: String?
) : View("declarationNodeView.ftl")