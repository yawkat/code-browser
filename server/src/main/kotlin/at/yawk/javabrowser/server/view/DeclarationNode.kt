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
        val children: Iterator<DeclarationNode>?,

        val diffResult: DiffResult? = null
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

    val deprecated: Boolean
        get() = (modifiers and BindingDecl.MODIFIER_DEPRECATED) != 0

    enum class DiffResult {
        UNCHANGED,
        CHANGED_INTERNALLY,
        INSERTION,
        DELETION,
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