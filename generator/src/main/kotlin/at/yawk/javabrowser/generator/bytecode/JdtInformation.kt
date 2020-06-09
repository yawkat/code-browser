package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.Type

internal class JdtInformation {
    val missingTypes = mutableListOf<Type>()

    fun isMissing(type: Type): Boolean = when (type.sort) {
        Type.ARRAY -> isMissing(type.elementType)
        Type.METHOD -> type.argumentTypes.any { isMissing(it) } || isMissing(type.returnType)
        else -> type in missingTypes
    }
}