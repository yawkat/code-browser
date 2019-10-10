package at.yawk.javabrowser.generator

import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IPackageBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

/**
 * @author yawkat
 */
object Bindings {
    /**
     * A binding that is unique to the source file. Used for places where no other binding ID is available,
     * specifically initializer blocks and lambda expressions
     */
    fun toStringKeyBinding(binding: IBinding): String = binding.key

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

    fun toString(packageBinding: IPackageBinding): String? =
            if (packageBinding.isUnnamed) null
            else packageBinding.name

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