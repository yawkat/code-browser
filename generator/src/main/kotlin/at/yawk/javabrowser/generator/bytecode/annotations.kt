package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type


abstract class BaseAnnotationPrinter(protected val printer: BytecodePrinter) : AnnotationVisitor(Opcodes.ASM8) {
    abstract fun member(name: String?)

    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        member(name)
        return FullAnnotationPrinter(printer, descriptor)
    }

    override fun visitEnum(name: String?, descriptor: String, value: String) {
        member(name)
        printer.appendDescriptor(Type.getType(descriptor)).append('.').append(value) // TODO link enum value
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
        printer.appendJavaName(Type.getType(descriptor)).append('(')
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