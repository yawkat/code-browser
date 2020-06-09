package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.generator.GeneratorSourceFile
import org.apache.commons.lang3.StringEscapeUtils

class BytecodePrinter {
    private val annotations: MutableList<PositionedAnnotation> = ArrayList()
    private val text = StringBuilder()

    private var runningRefCounter = 0

    internal fun finishString() = text.toString()

    fun finish(): GeneratorSourceFile {
        val sourceFile = GeneratorSourceFile(finishString(), annotations)
        sourceFile.bake()
        return sourceFile
    }

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
            annotate(Style("string-literal")) {
                text.append('"').append(StringEscapeUtils.escapeJava(value)).append('"')
            }
        } else {
            annotate(Style("number-literal")) {
                text.append(value)
            }
        }
    }

    fun position() = text.length

    fun createBindingRef(type: BindingRefType, target: String) = BindingRef(type, target, runningRefCounter++)

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
    val bytecodeAccess = access and 0xffff // mask asm-specific flags (deprecated, record)

    // flags: (0x0420) ACC_SUPER, ACC_ABSTRACT
    append("flags: (0x").append(String.format("%04x", bytecodeAccess)).append(") ")
    var accessFound = 0
    var first = true
    for (flag in Flag.FLAGS) {
        if (target in flag.targets && (flag.opcode and bytecodeAccess) != 0) {
            if (!first) append(", ")
            first = false
            append(flag.id)
            accessFound = accessFound or flag.opcode
        }
    }
    append('\n')
    if (accessFound != bytecodeAccess) {
        throw AssertionError("Invalid or unknown access modifier in ${bytecodeAccess.toString(16)} for $target")
    }
}