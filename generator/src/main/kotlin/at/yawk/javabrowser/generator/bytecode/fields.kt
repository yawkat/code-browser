package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRefType
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldNode

internal fun printField(printer: BytecodePrinter, owner: Type, field: FieldNode) {
    val type = Type.getType(field.desc)
    printer.indent(1)
    printer.printSourceModifiers(field.access, Flag.Target.FIELD, trailingSpace = true)
    if (field.signature != null) {
        printer.printJavaSignature(field.signature, access = field.access, isTypeSignature = true)
    } else {
        printer.appendJavaName(type, BindingRefType.FIELD_TYPE)
    }
    printer.append(' ')
    printer.annotate(BindingDecl(
            binding = BytecodeBindings.toStringField(owner, field.name, type),
            description = BindingDecl.Description.Field(
                    name = field.name,
                    typeBinding = typeDescription(type)
            ),
            modifiers = asmAccessToSourceAnnotation(field.access),
            parent = BytecodeBindings.toStringClass(owner),
            superBindings = emptyList()
    )) {
        printer.append(field.name)
    }
    printer.append(";\n")

    printer.indent(2)
    printer.append("descriptor: ").appendDescriptor(type, BindingRefType.FIELD_TYPE, duplicate = true).append('\n')
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