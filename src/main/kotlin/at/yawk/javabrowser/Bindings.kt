package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

/**
 * @author yawkat
 */
object Bindings {
    fun toString(typeBinding: ITypeBinding): String? = try {
        typeBinding.qualifiedName
    } catch (e: UnsupportedOperationException) {
        null
    }

    fun toString(typeBinding: IVariableBinding): String? {
        return (toString(typeBinding.declaringClass) ?: return null) + "#" + typeBinding.name
    }

    fun toString(typeBinding: IMethodBinding): String? {
        val builder = StringBuilder(toString(typeBinding.declaringClass) ?: return null)
        if (!typeBinding.isConstructor) {
            builder.append('#')
            builder.append(typeBinding.name)
        }
        builder.append('(')
        for ((i, parameterType) in typeBinding.parameterTypes.withIndex()) {
            if (i > 0) builder.append(',')
            builder.append(toString(parameterType) ?: return null)
        }
        builder.append(')')
        return builder.toString()
    }
}