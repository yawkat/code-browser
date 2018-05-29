package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding

/**
 * @author yawkat
 */
object Bindings {
    fun toString(typeBinding: ITypeBinding) = typeBinding.binaryName!!
    fun toString(typeBinding: IVariableBinding) = typeBinding.declaringClass.binaryName + "#" + typeBinding.name
    fun toString(typeBinding: IMethodBinding) = typeBinding.declaringClass.binaryName + "#" + typeBinding.name +
            "(" + typeBinding.parameterTypes.joinToString(",") { it.binaryName } + ")"
}