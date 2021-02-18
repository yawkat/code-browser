package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.Realm

/**
 * @author yawkat
 */
data class DeclarationNode(
        val realm: Realm,
        val artifactId: String,
        val parent: BindingId?,
        val bindingId: BindingId,
        val binding: String,
        val description: BindingDecl.Description,
        val modifiers: Int,

        /**
         * Source file path to this binding, including potential diff query parameter, but excluding the hash to the
         * specific binding.
         */
        val fullSourceFilePath: String? = null,

        /**
         * Once an element has been hasNext'd, previous items may become inaccessible. If this is `null`, children
         * should be lazy-loaded.
         */
        val children: Iterator<DeclarationNode>?,

        val diffResult: DiffResult? = null
) {
    companion object {
        /**
         * The comparator used for display ordering. Have to be careful here to preserve source file order for bindings
         * that reside in .java files, but at the same time keep a total order for items at the package level.
         */
        val DISPLAY_ORDER = Comparator.comparing<DeclarationNode, Realm> { it.realm }
            .thenComparing<String> { it.artifactId }
            .thenComparing<String> {
                if (it.description is BindingDecl.Description.Package) it.binding
                else it.fullSourceFilePath ?: it.binding
            }!!
    }

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