package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingDecl
import org.objectweb.asm.Type

val Type.simpleName: String
    get() = className.let { it.substring(it.lastIndexOf('.') + 1) }

internal fun typeDescription(printer: BytecodePrinter, type: Type): BindingDecl.Description.Type = BindingDecl.Description.Type(
        kind = BindingDecl.Description.Type.Kind.CLASS,
        binding = if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) printer.hashBinding(BytecodeBindings.toStringClass(type)) else null,
        simpleName = type.simpleName
)
