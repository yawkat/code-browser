package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

/**
 * @author yawkat
 */
object Bindings {
    fun toString(typeBinding: ITypeBinding): String? {
        try {
            val decl = typeBinding.typeDeclaration.erasure ?: return null
            val qname = decl.qualifiedName
            // empty for local / anon types
            @Suppress("LiftReturnOrAssignment")
            if (qname.isEmpty()) {
                return decl.binaryName
            } else {
                return qname
            }
        } catch (e: UnsupportedOperationException) {
            return null
        }
    }

    fun toString(varBinding: IVariableBinding): String? {
        val decl = varBinding.variableDeclaration ?: return null
        if (decl.declaringClass == null) return null // array length for example
        return (toString(decl.declaringClass) ?: return null) + "#" + decl.name
    }

    fun toString(methodBinding: IMethodBinding): String? {
        val decl = methodBinding.methodDeclaration ?: return null
        val builder = StringBuilder(toString(decl.declaringClass) ?: return null)
        if (!decl.isConstructor) {
            builder.append('#')
            builder.append(decl.name)
        }
        builder.append('(')
        for ((i, parameterType) in decl.parameterTypes.withIndex()) {
            if (i > 0) builder.append(',')
            builder.append(toString(parameterType) ?: return null)
        }
        builder.append(')')
        return builder.toString()
    }
}