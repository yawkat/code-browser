package at.yawk.javabrowser.generator.source

import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IPackageBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

private val LAMBDA_METHOD_CLASS = Class.forName("org.eclipse.jdt.core.dom.MethodBinding\$LambdaMethod")
private val LAMBDA_METHOD_IMPL_FIELD = LAMBDA_METHOD_CLASS.declaredFields.single { it.name == "implementation" }.also {
    it.isAccessible = true
}

/**
 * [org.eclipse.jdt.core.dom.MethodBinding.LambdaMethod.implementation]
 */
internal fun unsafeGetImplementation(binding: IMethodBinding) =
        LAMBDA_METHOD_IMPL_FIELD.get(binding) as IMethodBinding

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
        var decl = methodBinding.methodDeclaration ?: return null
        if (LAMBDA_METHOD_CLASS.isInstance(decl)) {
            decl = unsafeGetImplementation(decl)
        }
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