package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.generator.Bindings
import org.objectweb.asm.Type


/**
 * @param duplicate If `true`, this descriptor is redundant with data already printed. todo: implement
 */
fun BytecodePrinter.appendDescriptor(type: Type, refType: BindingRefType, duplicate: Boolean = false): BytecodePrinter {
    when (type.sort) {
        Type.OBJECT -> {
            annotate(createBindingRef(refType, Bindings.toStringClass(type))) {
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
 */
fun BytecodePrinter.appendMember(owner: Type, name: String, type: Type, refType: BindingRefType): BytecodePrinter {
    val ref =
            if (type.sort == Type.METHOD) Bindings.toStringMethod(owner, name, type)
            else Bindings.toStringField(owner, name, type)
    appendJavaName(owner, BindingRefType.MEMBER_REFERENCE_QUALIFIER).append('.')
    annotate(createBindingRef(refType, ref)) {
        append(name)
    }
    append(':').appendDescriptor(type, BindingRefType.MEMBER_REFERENCE_QUALIFIER)
    return this
}

/**
 * @param duplicate If `true`, this descriptor is redundant with data already printed. todo: implement
 */
fun BytecodePrinter.appendJavaName(type: Type, refType: BindingRefType, duplicate: Boolean = false): BytecodePrinter {
    require(type.sort != Type.METHOD)
    when (type.sort) {
        Type.ARRAY -> {
            appendJavaName(type.elementType, refType)
            append("[]")
        }
        Type.OBJECT -> {
            annotate(createBindingRef(refType, Bindings.toStringClass(type))) {
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