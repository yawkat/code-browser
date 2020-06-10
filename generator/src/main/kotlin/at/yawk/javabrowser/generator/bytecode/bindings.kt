package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Style
import org.objectweb.asm.Type


/**
 * @param duplicate see [at.yawk.javabrowser.BindingRef.duplicate]
 */
fun BytecodePrinter.appendDescriptor(type: Type, refType: BindingRefType, duplicate: Boolean = false): BytecodePrinter {
    when (type.sort) {
        Type.OBJECT -> {
            annotate(createBindingRef(refType, BytecodeBindings.toStringClass(type), duplicate)) {
                append(type.descriptor)
            }
        }
        Type.ARRAY -> {
            append('[')
            appendDescriptor(type.elementType, refType)
        }
        Type.METHOD -> appendMethodDescriptor(type, refType, refType, duplicate)
        else -> append(type.descriptor.single())
    }
    return this
}

fun BytecodePrinter.appendMethodDescriptor(type: Type,
                                           paramRefType: BindingRefType,
                                           returnRefType: BindingRefType,
                                           duplicate: Boolean = false): BytecodePrinter {
    require(type.sort == Type.METHOD)
    append('(')
    for (argumentType in type.argumentTypes) {
        appendDescriptor(argumentType, paramRefType, duplicate)
    }
    append(')')
    appendDescriptor(type.returnType, returnRefType, duplicate)
    return this
}

/**
 * Append a field or method reference.
 *
 * @param type The type of this member. [Type.METHOD] for methods, any other for fields.
 * @param duplicate see [at.yawk.javabrowser.BindingRef.duplicate]
 */
fun BytecodePrinter.appendMember(owner: Type, name: String, type: Type, refType: BindingRefType, duplicate: Boolean = false): BytecodePrinter {
    val ref =
            if (type.sort == Type.METHOD) BytecodeBindings.toStringMethod(owner, name, type)
            else BytecodeBindings.toStringField(owner, name, type)
    appendJavaName(owner, BindingRefType.MEMBER_REFERENCE_QUALIFIER).append('.')
    annotate(createBindingRef(refType, ref, duplicate)) {
        append(name)
    }
    append(':').appendDescriptor(type, BindingRefType.MEMBER_REFERENCE_QUALIFIER)
    return this
}

/**
 * @param duplicate see [at.yawk.javabrowser.BindingRef.duplicate]
 */
fun BytecodePrinter.appendJavaName(type: Type, refType: BindingRefType, duplicate: Boolean = false): BytecodePrinter {
    require(type.sort != Type.METHOD)
    when (type.sort) {
        Type.ARRAY -> {
            appendJavaName(type.elementType, refType)
            append("[]")
        }
        Type.OBJECT -> {
            annotate(createBindingRef(refType, BytecodeBindings.toStringClass(type), duplicate)) {
                append(type.className)
            }
        }
        else -> {
            annotate(Style("keyword")) {
                append(type.className)
            }
        }
    }
    return this
}

fun BytecodePrinter.appendJavaPackageName(type: Type): BytecodePrinter {
    require(type.sort == Type.OBJECT)
    append(type.className) // do not link - package name, not class name
    return this
}

fun BytecodePrinter.appendGenericSignature(signature: String): BytecodePrinter {
    // TODO: link
    append(signature)
    return this
}