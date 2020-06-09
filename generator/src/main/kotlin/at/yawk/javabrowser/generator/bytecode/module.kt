package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingRefType
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ModuleNode

// this does not match javap at all, because javap is terrible at printing modules.

internal fun printModuleAttributes(printer: BytecodePrinter, module: ModuleNode) {
    printer.append("Module:").append('\n')
    printer.indent(1).append("Name: ").append(module.name).append('\n')
    if (module.version != null) {
        printer.indent(1).append("Version: ").append(module.version).append('\n')
    }
    printer.indent(1).printFlags(module.access, Flag.Target.MODULE)
    if (module.mainClass != null) {
        printer.indent(1).append("MainClass: ")
                .appendJavaName(Type.getObjectType(module.mainClass), BindingRefType.MAIN_CLASS)
                .append('\n')
    }
    if (!module.packages.isNullOrEmpty()) {
        printer.indent(1).append("Packages:\n")
        for (pkg in module.packages) {
            printer.indent(2).appendJavaPackageName(Type.getObjectType(pkg)).append('\n')
        }
    }
}

internal fun printModuleBody(printer: BytecodePrinter, module: ModuleNode) {
    for (require in module.requires ?: emptyList()) {
        printer.indent(1).append("requires ")
        printer.printSourceModifiers(require.access, Flag.Target.REQUIRES, trailingSpace = true)
        printer.append(require.module)
        if (require.version != null) {
            printer.append('@').append(require.version)
        }
        printer.append("; // ")
        printer.printFlags(require.access, Flag.Target.REQUIRES)
    }
    printExportsOpens(
            printer,
            verb = "exports",
            flagTarget = Flag.Target.EXPORTS,
            nodes = module.exports,
            access = { access },
            packaze = { packaze },
            modules = { modules }
    )
    printExportsOpens(
            printer,
            verb = "opens",
            flagTarget = Flag.Target.OPENS,
            nodes = module.opens,
            access = { access },
            packaze = { packaze },
            modules = { modules }
    )
    for (provide in module.provides ?: emptyList()) {
        printer.indent(1).append("provides ")
                .appendJavaName(Type.getObjectType(provide.service), BindingRefType.SPI_PROVIDER)
                .append(" with \n")
        for ((i, impl) in provide.providers.withIndex()) {
            printer.indent(2)
            printer.appendJavaName(Type.getObjectType(impl), BindingRefType.SPI_PROVIDER)
            if (i == provide.providers.lastIndex) {
                printer.append(";\n")
            } else {
                printer.append(",\n")
            }
        }
    }
}

private inline fun <N> printExportsOpens(
        printer: BytecodePrinter,
        verb: String,
        flagTarget: Flag.Target,
        nodes: List<N>?,
        packaze: N.() -> String,
        access: N.() -> Int,
        modules: N.() -> List<String>?
) {
    for (open in nodes ?: emptyList()) {
        printer.indent(1).append(verb).append(' ')
        printer.printSourceModifiers(open.access(), flagTarget, trailingSpace = true)
        printer.appendJavaPackageName(Type.getObjectType(open.packaze()))
        val targets = open.modules() ?: emptyList()
        val targetCount = targets.size
        when (targetCount) {
            0 -> printer.append("; // ")
            1 -> printer.append(" to ").append(targets.single()).append(';').append(" // ")
            else -> printer.append(" to // ")
        }
        printer.printFlags(open.access(), Flag.Target.OPENS)
        if (targetCount >= 2) {
            for ((i, target) in targets.withIndex()) {
                printer.indent(2)
                printer.append(target)
                if (i == targets.lastIndex) {
                    printer.append(";\n")
                } else {
                    printer.append(",\n")
                }
            }
        }
    }
}