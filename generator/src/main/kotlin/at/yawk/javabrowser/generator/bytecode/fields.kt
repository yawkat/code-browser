package at.yawk.javabrowser.generator.bytecode

import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldNode

fun printField(printer: BytecodePrinter, field: FieldNode) {
    val type = Type.getType(field.desc)
    printer.indent(1)
    printer.printSourceModifiers(field.access, Flag.Target.FIELD, trailingSpace = true)
    if (field.signature != null) {
        printer.printJavaSignature(field.signature, access = field.access, isTypeSignature = true)
    } else {
        printer.appendJavaName(type)
    }
    printer.append(' ').append(field.name).append(";\n")

    printer.indent(2)
    printer.append("descriptor: ").appendDescriptor(type).append('\n')
    printer.indent(2)
    printer.printFlags(field.access, Flag.Target.FIELD)
    if (field.signature != null) {
        printer.indent(2)
        printer.append("Signature: ").appendGenericSignature(field.signature).append('\n')
    }

    if (field.value != null) {
        printer.indent(2)
        printer.append("ConstantValue: ").appendConstant(field.value)
        printer.append('\n')
    }

    printer.printAnnotations("RuntimeVisibleAnnotations", field.visibleAnnotations)
    printer.printAnnotations("RuntimeInvisibleAnnotations", field.invisibleAnnotations)

    printer.printTypeAnnotations("RuntimeVisibleTypeAnnotations", field.visibleTypeAnnotations ?: emptyList())
    printer.printTypeAnnotations("RuntimeInvisibleTypeAnnotations", field.invisibleTypeAnnotations ?: emptyList())

    // no unknown attrs
    require(field.attrs == null)
}