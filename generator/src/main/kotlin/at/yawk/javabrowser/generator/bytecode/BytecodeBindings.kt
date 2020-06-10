package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.Type

object BytecodeBindings {
    fun toStringClass(type: Type): String {
        require(type.sort == Type.OBJECT || type.sort == Type.ARRAY)
        return type.descriptor
    }

    fun toStringMethod(declaring: Type, name: String, type: Type): String {
        require(type.sort == Type.METHOD)
        return declaring.descriptor + '.' + name + ':' + type.descriptor
    }

    fun toStringField(declaring: Type, name: String, type: Type): String {
        require(type.sort != Type.METHOD)
        return declaring.descriptor + '.' + name + ':' + type.descriptor
    }
}