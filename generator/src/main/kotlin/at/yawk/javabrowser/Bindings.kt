package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

/**
 * @author yawkat
 */
object Bindings {
    fun toString(typeBinding: ITypeBinding): String? = try {
        val qname = typeBinding.typeDeclaration.qualifiedName
        if (qname.isEmpty()) null else qname
    } catch (e: UnsupportedOperationException) {
        null
    }

    fun toString(typeBinding: IVariableBinding): String? {
        return (toString(typeBinding.declaringClass) ?: return null) + "#" + typeBinding.name
    }

    fun toString(methodBinding: IMethodBinding): String? {
        val builder = StringBuilder(toString(methodBinding.declaringClass) ?: return null)
        if (!methodBinding.isConstructor) {
            builder.append('#')
            builder.append(methodBinding.name)
        }
        builder.append('(')
        for ((i, parameterType) in methodBinding.parameterTypes.withIndex()) {
            if (i > 0) builder.append(',')
            builder.append(toString(parameterType) ?: return null)
        }
        builder.append(')')
        return builder.toString()
    }
}