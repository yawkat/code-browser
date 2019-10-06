package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingDecl
import java.util.Collections

/**
 * Variant of [DeclarationNode] used for packages
 *
 * @author yawkat
 */
sealed class PackageNode(
        val artifactId: String,
        /**
         * @see [DeclarationNode.children]
         */
        val children: Iterator<PackageNode>
) {
    val canLoadChildren: Boolean
        // all package nodes should have children - if they don't, lazy load them.
        get() = !children.hasNext()

    abstract val fullName: String

    abstract val descriptionType: String

    class Package(
            artifactId: String,
            override val fullName: String,
            val relativeName: String,
            children: Iterator<PackageNode>
    ) : PackageNode(artifactId, children) {
        override val descriptionType: String
            get() = "package"
    }

    class Type(
            artifactId: String,
            private val declarationNode: DeclarationNode,
            val fullSourceFilePath: String
    ) : PackageNode(artifactId, Collections.emptyIterator()) {
        override val fullName: String
            get() = declarationNode.declaration.binding
        override val descriptionType: String
            get() = declarationNode.descriptionType
        val declaration: BindingDecl
            get() = declarationNode.declaration
    }
}