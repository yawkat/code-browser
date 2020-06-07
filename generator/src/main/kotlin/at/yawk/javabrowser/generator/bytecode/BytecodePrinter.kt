package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import org.apache.commons.lang3.StringEscapeUtils
import org.objectweb.asm.Type

class BytecodePrinter {
    private val annotations: MutableList<PositionedAnnotation> = ArrayList()
    private val text = StringBuilder()

    internal fun finishString() = text.toString()

    fun annotate(start: Int, length: Int, annotation: SourceAnnotation) {
        annotations.add(PositionedAnnotation(start, length, annotation))
    }

    fun append(o: String): BytecodePrinter {
        text.append(o)
        return this
    }

    fun append(o: Char): BytecodePrinter {
        text.append(o)
        return this
    }

    fun append(o: Int): BytecodePrinter {
        text.append(o)
        return this
    }

    fun appendConstant(value: Any) {
        if (value is String) {
            text.append('"').append(StringEscapeUtils.escapeJava(value)).append('"')
        } else {
            text.append(value)
        }
    }

    fun appendDescriptor(type: Type): BytecodePrinter {
        text.append(type.descriptor) // TODO: link
        return this
    }

    /**
     * Append a field or method reference.
     *
     * @param type The type of this member. [Type.METHOD] for methods, any other for fields.
     */
    fun appendMember(owner: Type, name: String, type: Type): BytecodePrinter {
        appendJavaName(owner)
        append('.').append(name) // TODO: link
        append(':').appendDescriptor(type)
        return this
    }

    fun appendInternalName(type: Type): BytecodePrinter {
        require(type.sort == Type.OBJECT || type.sort == Type.ARRAY)
        text.append(type.internalName) // TODO: link
        return this
    }

    fun appendJavaName(type: Type): BytecodePrinter {
        require(type.sort != Type.METHOD)
        text.append(type.className) // TODO: link
        return this
    }

    fun appendJavaPackageName(type: Type): BytecodePrinter {
        require(type.sort != Type.METHOD)
        text.append(type.className) // do not link - package name, not class name
        return this
    }

    fun appendGenericSignature(signature: String): BytecodePrinter {
        // TODO: link
        text.append(signature)
        return this
    }

    fun position() = text.length

    inline fun annotate(annotation: SourceAnnotation, ignoreEmpty: Boolean = true, f: () -> Unit) {
        val start = position()
        f()
        val end = position()
        val length = end - start
        if (!ignoreEmpty || length > 0) {
            annotate(start, length, annotation)
        }
    }

    tailrec fun indent(depth: Int): BytecodePrinter {
        if (depth > 0) {
            text.append("  ")
            return indent(depth - 1)
        }
        return this
    }
}

fun BytecodePrinter.printSourceModifiers(access: Int, target: Flag.Target, trailingSpace: Boolean) {
    annotate(Style("keyword")) {
        var first = true
        for (flag in Flag.FLAGS) {
            if (target in flag.targets && (flag.opcode and access) != 0 && flag.modifier != null) {
                if (!first) {
                    append(' ')
                }
                first = false
                append(flag.modifier)
            }
        }
        if (!first && trailingSpace) {
            append(' ')
        }
    }
}

fun BytecodePrinter.printFlags(access: Int, target: Flag.Target) {
    // flags: (0x0420) ACC_SUPER, ACC_ABSTRACT
    append("flags: (0x").append(String.format("%04x", access)).append(") ")
    var accessFound = 0
    var first = true
    for (flag in Flag.FLAGS) {
        if (target in flag.targets && (flag.opcode and access) != 0) {
            if (!first) append(", ")
            first = false
            append(flag.id)
            accessFound = accessFound or flag.opcode
        }
    }
    append('\n')
    if (accessFound != access) {
        throw AssertionError("Invalid or unknown access modifier in ${access.toString(16)} for $target")
    }
}