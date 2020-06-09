package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingRefType
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import org.objectweb.asm.TypeReference
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.LocalVariableAnnotationNode
import org.objectweb.asm.tree.TypeAnnotationNode


abstract class BaseAnnotationPrinter(protected val printer: BytecodePrinter) : AnnotationVisitor(Opcodes.ASM8) {
    abstract fun member(name: String?)

    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        member(name)
        return FullAnnotationPrinter(printer, descriptor)
    }

    override fun visitEnum(name: String?, descriptor: String, value: String) {
        member(name)
        val enumType = Type.getType(descriptor)
        printer.appendMember(
                enumType,
                value,
                enumType,
                BindingRefType.ANNOTATION_MEMBER_VALUE
        )
    }

    override fun visit(name: String?, value: Any) {
        member(name)
        printer.appendConstant(value)
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        member(name)
        return ArrayAnnotationPrinter(printer)
    }
}

class ArrayAnnotationPrinter(printer: BytecodePrinter) : BaseAnnotationPrinter(printer) {
    private var first = true

    init {
        printer.append('{')
    }

    override fun visitEnd() {
        printer.append('}')
    }

    override fun member(name: String?) {
        require(name == null)
        if (!first) {
            printer.append(", ")
        }
        first = false
    }
}

open class FullAnnotationPrinter(
        printer: BytecodePrinter,

        descriptor: String,
        private var trailingNewLine: Boolean = false
) : BaseAnnotationPrinter(printer) {
    private var first = true

    init {
        printer.appendJavaName(Type.getType(descriptor), BindingRefType.ANNOTATION_TYPE).append('(')
    }

    override fun visitEnd() {
        printer.append(')')
        if (trailingNewLine) {
            printer.append('\n')
        }
    }

    override fun member(name: String?) {
        require(name != null)
        if (!first) {
            printer.append(", ")
        }
        first = false
        printer.append(name).append(" = ")
    }
}

fun BytecodePrinter.printAnnotations(name: String, list: List<AnnotationNode>?) {
    if (list == null) {
        return
    }

    indent(2)
    append(name).append(": \n")
    for (annotationNode in list) {
        indent(3)
        annotationNode.accept(FullAnnotationPrinter(this, annotationNode.desc, trailingNewLine = true))
    }
}

fun BytecodePrinter.printTypeAnnotations(
        name: String,
        allNodes: List<TypeAnnotationNode>?,
        printLocalScope: (LocalVariableAnnotationNode) -> Unit = { throw UnsupportedOperationException() }
) {
    if (allNodes.isNullOrEmpty()) {
        return
    }

    indent(2)
    append(name)
    append(": \n")
    for (annotationNode in allNodes) {
        indent(3)
        val typeReference = TypeReference(annotationNode.typeRef)
        append(sortToString(typeReference))
        val typePath = annotationNode.typePath
        if (typePath != null) {
            append(", location=[")
            for (i in 0 until typePath.length) {
                if (i != 0) {
                    append(", ")
                }
                append(typePathStepToString(typePath.getStep(i)))
                if (typePath.getStep(i) == TypePath.TYPE_ARGUMENT) {
                    append('(').append(typePath.getStepArgument(i)).append(')')
                }
            }
            append(']')
        }
        if (annotationNode is LocalVariableAnnotationNode) {
            printLocalScope(annotationNode)
        }
        append('\n')
        indent(4)
        val visitor = FullAnnotationPrinter(this, annotationNode.desc, trailingNewLine = true)
        annotationNode.accept(visitor)
    }
}