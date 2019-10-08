package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingDecl

/**
 * @author yawkat
 */
data class DeclarationNode(
        val artifactId: String,
        val binding: String,
        val description: BindingDecl.Description,
        val modifiers: Int,

        val fullSourceFilePath: String? = null,

        /**
         * Once an element has been hasNext'd, previous items may become inaccessible. If this is `null`, children
         * should be lazy-loaded.
         */
        val children: Iterator<DeclarationNode>?
) {
    val kind: Kind
        get() = when (description) {
            is BindingDecl.Description.Type -> Kind.TYPE
            is BindingDecl.Description.Lambda -> Kind.LAMBDA
            is BindingDecl.Description.Initializer -> Kind.INITIALIZER
            is BindingDecl.Description.Method -> Kind.METHOD
            is BindingDecl.Description.Field -> Kind.FIELD
            is BindingDecl.Description.Package -> Kind.PACKAGE
        }

    enum class Kind {
        PACKAGE,

        TYPE,
        LAMBDA,
        METHOD,
        FIELD,
        INITIALIZER
    }
}