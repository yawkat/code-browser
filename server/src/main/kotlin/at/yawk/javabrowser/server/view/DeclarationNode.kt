package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.BindingDecl

/**
 * @author yawkat
 */
data class DeclarationNode(
        val declaration: BindingDecl,
        /**
         * Once an element has been hasNext'd, previous items may become inaccessible.
         */
        val children: Iterator<DeclarationNode>
) {
    val descriptionType: String
        get() = when (declaration.description) {
            is BindingDecl.Description.Type -> "type"
            is BindingDecl.Description.Lambda -> "lambda"
            is BindingDecl.Description.Initializer -> "initializer"
            is BindingDecl.Description.Method -> "method"
            is BindingDecl.Description.Field -> "field"
        }
}