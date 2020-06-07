package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.Style
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode

class ClassPrinter private constructor(
        private val printer: BytecodePrinter,
        private val sourceFilePath: String

// we extend class node to make some stuff easier but we don't use it for fields or methods
) : ClassNode(Opcodes.ASM8) {
    companion object {
        fun visitor(
                printer: BytecodePrinter,
                sourceFilePath: String
        ): ClassVisitor = ClassPrinter(printer, sourceFilePath)
    }

    override fun visit(version: Int,
                       access: Int,
                       name: String,
                       signature: String?,
                       superName: String?,
                       interfaces: Array<String>?) {
        super.visit(version, access, name, signature, superName, interfaces)

        val isInterface = (access and Opcodes.ACC_INTERFACE) != 0

        printer.printSourceModifiers(
                if (isInterface) access and Opcodes.ACC_ABSTRACT.inv() else access,
                Flag.Target.CLASS, trailingSpace = true)
        printer.append(if (isInterface) "interface " else "class ")
        printer.appendJavaName(Type.getObjectType(name))
        if (signature != null) {
            printer.printJavaSignature(signature, access = access, isTypeSignature = false)
            printer.append('\n')
        } else {
            if (superName != null && superName != "java/lang/Object") {
                printer.append(' ')
                printer.annotate(Style("keyword")) { printer.append("extends") }
                printer.append(' ').appendJavaName(Type.getObjectType(superName))
            }
            if (!interfaces.isNullOrEmpty()) {
                printer.append(' ')
                printer.annotate(Style("keyword")) { printer.append(if (isInterface) "extends" else "implements") }
                printer.append(' ')
                for ((i, itf) in interfaces.withIndex()) {
                    if (i != 0) printer.append(", ")
                    printer.appendJavaName(Type.getObjectType(itf))
                }
            }
            printer.append('\n')
        }
        printer.indent(1)
        printer.append("minor version: ").append(version ushr 16).append('\n')
        printer.indent(1)
        printer.append("major version: ").append(version and 0xffff).append('\n')
        printer.indent(1)
        printer.append("flags: ").printFlags(access, Flag.Target.CLASS)
        printer.indent(1)
        printer.append("this_class: ").appendJavaName(Type.getObjectType(name)).append('\n')
        if (superName != null) {
            printer.indent(1)
            printer.append("super_class: ").appendJavaName(Type.getObjectType(superName)).append('\n')
        }
        printer.append("{\n")
    }

    override fun visitEnd() {
        super.visitEnd()
        printer.append("}\n")

        if (this.signature != null) {
            printer.append("Signature: ").appendGenericSignature(this.signature).append('\n')
        }
        require(this.sourceDebug == null)
        if (this.sourceFile != null) {
            printer.append("SourceFile: \"").append(this.sourceFile).append("\"\n")
        }
        if (this.outerMethod != null) {
            printer.append("EnclosingMethod: ")
                    .appendMember(Type.getObjectType(this.outerClass), this.outerMethod, Type.getMethodType(this.outerMethodDesc))
                    .append('\n')
        } else if (this.outerClass != null) {
            printer.append("EnclosingMethod: ")
                    .appendJavaName(Type.getObjectType(this.outerClass))
                    .append('\n')
        }
        if (!this.nestMembers.isNullOrEmpty()) {
            printer.append("NestMembers:\n")
            for (nestMember in this.nestMembers) {
                printer.indent(1)
                printer.appendJavaName(Type.getObjectType(nestMember))
            }
        }
        if (this.nestHostClass != null) {
            printer.append("NestHost: ").appendJavaName(Type.getObjectType(this.nestHostClass)).append('\n')
        }
        if (!this.innerClasses.isNullOrEmpty()) {
            printer.append("InnerClasses:\n")
            for (innerClass in this.innerClasses) {
                printer.indent(1)
                printer.printSourceModifiers(innerClass.access, Flag.Target.CLASS, trailingSpace = true)
                if (innerClass.innerName != null) {
                    printer.append(innerClass.innerName).append(" = ")
                }
                printer.appendJavaName(Type.getObjectType(innerClass.name))
                if (innerClass.outerName != null) {
                    printer.append(" of ").appendJavaName(Type.getObjectType(innerClass.outerName))
                }
                printer.append('\n')
            }
        }
        printer.printAnnotations("RuntimeVisibleAnnotations", visibleAnnotations)
        printer.printAnnotations("RuntimeInvisibleAnnotations", invisibleAnnotations)
        printer.printTypeAnnotations("RuntimeVisibleTypeAnnotations", visibleTypeAnnotations)
        printer.printTypeAnnotations("RuntimeInvisibleTypeAnnotations", invisibleTypeAnnotations)
    }

    private var firstMember = true

    override fun visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String?,
                             exceptions: Array<String>?): MethodVisitor {
        if (!firstMember) printer.append('\n')
        firstMember = false
        return MethodPrinter.visitor(
                printer = printer,
                methodOwnerType = Type.getObjectType(this.name),
                sourceFilePath = sourceFilePath,
                access = access,
                name = name,
                descriptor = descriptor,
                signature = signature,
                exceptions = exceptions
        )
    }

    override fun visitField(access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            value: Any?): FieldVisitor {
        if (!firstMember) printer.append('\n')
        firstMember = false
        return object : FieldNode(Opcodes.ASM8, access, name, descriptor, signature, value) {
            override fun visitEnd() {
                super.visitEnd()
                printField(printer, this)
            }
        }
    }

    override fun visitModule(name: String?, access: Int, version: String?): ModuleVisitor {
        TODO()
    }

    override fun visitRecordComponent(name: String?, descriptor: String?, signature: String?): RecordComponentVisitor {
        throw UnsupportedOperationException()
    }

    override fun visitAttribute(attribute: Attribute?) {
        throw UnsupportedOperationException()
    }
}