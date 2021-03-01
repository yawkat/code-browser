package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.LocalVariableOrLabelRef
import at.yawk.javabrowser.SourceLineRef
import at.yawk.javabrowser.Style
import com.google.common.hash.Hasher
import com.google.common.hash.Hashing
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableAnnotationNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.ParameterNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeAnnotationNode
import kotlin.math.max

private const val MAX_INT_LENGTH = Int.MIN_VALUE.toString().length

internal class MethodPrinter private constructor(
        private val printer: BytecodePrinter,
        private val node: MethodNode,

        private val methodOwnerType: Type,
        private val sourceFilePath: String,
        private val jdtInformation: JdtInformation
) {
    companion object {
        fun visitor(
                printer: BytecodePrinter,
                methodOwnerType: Type,
                sourceFilePath: String,
                jdtInformation: JdtInformation,

                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<String>?
        ): MethodVisitor = object : MethodNode(Opcodes.ASM8, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()
                MethodPrinter(printer, this, methodOwnerType, sourceFilePath, jdtInformation).print()
            }
        }
    }

    private val methodType = Type.getMethodType(node.desc)
    private val labels = node.instructions.filterIsInstance<LabelNode>().map { it.label }

    @Suppress("UnstableApiUsage")
    private fun Hasher.putLengthPrefixedString(s: String) {
        putInt(s.length)
        putUnencodedChars(s)
    }

    @Suppress("UnstableApiUsage")
    private fun Hasher.uniqueForMethod(): Hasher {
        putLengthPrefixedString(methodOwnerType.descriptor)
        putLengthPrefixedString(node.name)
        putLengthPrefixedString(node.desc)
        return this
    }

    @Suppress("UnstableApiUsage")
    private fun printLabel(label: Label, padStart: Int = 0) {
        val index = labels.indexOf(label)
        val annotation = LocalVariableOrLabelRef(java.lang.Long.toHexString(
                Hashing.goodFastHash(64).newHasher()
                        .uniqueForMethod()
                        .putInt(index)
                        .hash().asLong()
        ))
        printer.annotate(Style("number-literal")) {
            printer.annotate(annotation) { printer.append(index.toString().padStart(padStart)) }
        }
    }

    @Suppress("UnstableApiUsage")
    private fun getLocalVariableAnnotation(lv: LocalVariableNode) =
            LocalVariableOrLabelRef(Hashing.goodFastHash(64).newHasher()
                    .uniqueForMethod()
                    .putInt(labels.indexOf(lv.start.label))
                    .putInt(labels.indexOf(lv.end.label))
                    .putInt(lv.index)
                    .hash().asLong().toString(16))

    fun print() {
        // abstract java.lang.String x(java.lang.String, java.lang.String);
        printer.indent(1)
        printer.printSourceModifiers(node.access, Flag.Target.METHOD, trailingSpace = true)

        val decl = if (jdtInformation.isMissing(methodType)) null else {
            val binding = BytecodeBindings.toStringMethod(
                    declaring = methodOwnerType,
                    name = node.name,
                    type = methodType
            )
            val id = printer.hashBinding(binding)
            BindingDecl(
                    id = id,
                    binding = binding,
                    superBindings = emptyList(),
                    parent = printer.hashBinding(BytecodeBindings.toStringClass(methodOwnerType)),
                    modifiers = asmAccessToSourceAnnotation(node.access),
                    description = BindingDecl.Description.Method(
                            name = node.name,
                            returnTypeBinding = typeDescription(printer, methodType.returnType),
                            parameterTypeBindings = methodType.argumentTypes.map { typeDescription(printer, it) }
                    ),
                    corresponding = printer.getCorresponding(id)
            )
        }

        if (node.signature != null) {
            // TODO: annotate
            printer.printMethodSignature(node.signature, node.access, node.name)
        } else {
            printer.appendJavaName(methodType.returnType, BindingRefType.RETURN_TYPE)
            printer.append(' ')
            if (decl == null) {
                printer.append(node.name)
            } else {
                printer.annotate(decl) { printer.append(node.name) }
            }
            printer.append('(')
            for ((i, argumentType) in methodType.argumentTypes.withIndex()) {
                if (i != 0) printer.append(", ")
                printer.appendJavaName(argumentType, BindingRefType.PARAMETER_TYPE)
            }
            printer.append(")")
        }
        printer.append(";\n")

        // descriptor: (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
        printer.indent(2)
        printer.append("descriptor: ")
        printer.appendMethodDescriptor(
                methodType,
                paramRefType = BindingRefType.PARAMETER_TYPE, returnRefType = BindingRefType.RETURN_TYPE, duplicate = true)
        printer.append('\n')

        printer.indent(2)
        printer.printFlags(node.access, Flag.Target.METHOD)

        if (node.instructions.size() > 0) {
            Code().visitAll()
        }

        if (node.exceptions.isNotEmpty()) {
            printer.indent(2)
            printer.append("Exceptions:\n")
            printer.indent(3)
            printer.annotate(Style("keyword")) { printer.append("throws ") }
            for ((i, exception) in node.exceptions.withIndex()) {
                if (i != 0) printer.append(", ")
                printer.appendJavaName(Type.getObjectType(exception), BindingRefType.THROWS_DECLARATION)
            }
            printer.append('\n')
        }

        if (node.signature != null) {
            // Signature: <T:Ljava/lang/Object;>(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            printer.indent(2)
            printer.append("Signature: ").appendGenericSignature(node.signature).append('\n')
        }

        if (node.annotationDefault != null) {
            printer.indent(2)
            printer.append("AnnotationDefault: ")
            // this could be done without a visitor but since AnnotationNode.accept is package-private this is easiest
            node.accept(object : MethodVisitor(Opcodes.ASM8) {
                override fun visitAnnotationDefault(): AnnotationVisitor {
                    return object : BaseAnnotationPrinter(printer) {
                        override fun member(name: String?) {
                            require(name == null)
                        }
                    }
                }
            })
            printer.append('\n')
        }

        printer.printAnnotations("RuntimeVisibleAnnotations", node.visibleAnnotations)
        printer.printAnnotations("RuntimeInvisibleAnnotations", node.invisibleAnnotations)
        printTypeAnnotations(
                "RuntimeVisibleTypeAnnotations",
                node.visibleTypeAnnotations,
                { it.visibleTypeAnnotations },
                node.visibleLocalVariableAnnotations
        )
        printTypeAnnotations(
                "RuntimeInvisibleTypeAnnotations",
                node.invisibleTypeAnnotations,
                { it.invisibleTypeAnnotations },
                node.invisibleLocalVariableAnnotations
        )
        printParameterAnnotations("RuntimeVisibleParameterAnnotations", node.visibleParameterAnnotations)
        printParameterAnnotations("RuntimeInvisibleParameterAnnotations", node.invisibleParameterAnnotations)

        val parameters: List<ParameterNode>? = node.parameters
        if (!parameters.isNullOrEmpty()) {
            printer.indent(2)
            printer.append("MethodParameters:\n")
            // for table padding
            val maxParamNameLength = max(parameters.map { it.name?.length ?: 0 }.max()!!, 4)
            printer.indent(3)
            printer.append("Name".padStart(maxParamNameLength)).append("  Flags\n")
            for (parameter in parameters) {
                printer.indent(3)
                printer.append((parameter.name ?: "").padEnd(maxParamNameLength))
                printer.append("  ")
                printer.printSourceModifiers(parameter.access, Flag.Target.PARAMETER, trailingSpace = false)
                printer.append('\n')
            }
        }
    }

    private fun printTypeAnnotations(
            name: String,
            topLevel: List<TypeAnnotationNode>?,
            fromCatch: (TryCatchBlockNode) -> List<TypeAnnotationNode>?,
            locals: List<LocalVariableAnnotationNode>?
    ) {
        val allNodes = (topLevel ?: emptyList()) +
                node.tryCatchBlocks.flatMap { fromCatch(it) ?: emptyList() } +
                (locals ?: emptyList())

        printer.printTypeAnnotations(name, allNodes, printLocalScope = { annotationNode ->
            printer.append(", {")
            require(annotationNode.start.size == annotationNode.index.size)
            require(annotationNode.start.size == annotationNode.end.size)
            for (i in annotationNode.start.indices) {
                if (i != 0) printer.append("; ")
                val start = annotationNode.start[i]
                val end = annotationNode.end[i]
                val index = annotationNode.index[i]
                val lv = node.localVariables.single { it.start == start && it.end == end && it.index == index }
                printer.append("start=")
                printLabel(lv.start.label)
                printer.append(", end=")
                printLabel(lv.end.label)
                printer.append(", index=")
                printLocalVariable(lv)
            }
            printer.append('}')
        })
    }

    private fun printParameterAnnotations(name: String, parameters: Array<List<AnnotationNode>?>?) {
        if (parameters == null) {
            return
        }

        printer.indent(2)
        printer.append(name).append(": \n")
        for ((i, annotations) in parameters.withIndex()) {
            printer.indent(3)
            printer.append(i).append(":\n")

            for (annotation in annotations ?: emptyList()) {
                printer.indent(4)
                annotation.accept(FullAnnotationPrinter(printer, annotation.desc, trailingNewLine = true))
            }
        }
    }

    private fun printLocalVariable(lv: LocalVariableNode) {
        val annotation = getLocalVariableAnnotation(lv)
        printer.annotate(annotation) { printer.append(lv.index) }
        lv.name?.let { name ->
            printer.annotate(Style("comment")) {
                printer.append(" /* ")
                printer.annotate(annotation) { printer.append(name) }
                printer.append(" */")
            }
        }
    }

    private inner class Code : MethodVisitor(Opcodes.ASM8) {
        private lateinit var currentInstruction: AbstractInsnNode

        private var currentLabel: Label? = null
        private var nextInstructionLabel: Label? = null
        private var nextInstructionLabelPrinted = false

        fun visitAll() {
            printer.indent(2)
            printer.append("Code:\n")

            printer.indent(3)
            var argsSize = methodType.argumentTypes.size
            if ((node.access and Opcodes.ACC_STATIC) == 0) {
                argsSize++ // this
            }
            printer.append("stack=").append(node.maxStack)
                    .append(", locals=").append(node.maxLocals)
                    .append(", args_size=").append(argsSize)
                    .append('\n')

            var insn: AbstractInsnNode? = node.instructions.first
            while (insn != null) {
                currentInstruction = insn
                insn.accept(this)
                insn = insn.next
            }

            // these still belong to the code section

            val localVariables = node.localVariables
            if (localVariables != null) {
                printer.indent(3)
                printer.append("LocalVariableTable:\n")
                printer.indent(4)
                val maxLocalNameLength = max(localVariables.map { it.name.length }.max() ?: 0, 4)
                printer.append("Start  End  Slot  ").append("Name".padStart(maxLocalNameLength))
                        .append("  Signature\n")
                for (localVariable in localVariables) {
                    printer.indent(4)
                    printLabel(localVariable.start.label, padStart = 5)
                    printLabel(localVariable.end.label, padStart = 5)
                    printer.annotate(getLocalVariableAnnotation(localVariable)) {
                        printer.append(localVariable.index.toString().padStart(6))
                        printer.append(localVariable.name.padStart(maxLocalNameLength + 2))
                    }
                    printer.append("  ")
                    if (localVariable.signature != null) {
                        printer.appendGenericSignature(localVariable.signature)
                    } else {
                        printer.appendDescriptor(Type.getType(localVariable.desc), BindingRefType.LOCAL_VARIABLE_TYPE)
                    }
                    printer.append('\n')
                }
            }

            val tryCatchBlocks = node.tryCatchBlocks
            if (!tryCatchBlocks.isNullOrEmpty()) {
                printer.indent(3)
                printer.append("Exception table:\n")
                printer.indent(4)
                printer.append("from    to  target  type\n")
                for (tryCatchBlock in tryCatchBlocks) {
                    printer.indent(4)
                    printLabel(tryCatchBlock.start.label, padStart = 4)
                    printer.append(' ')
                    printLabel(tryCatchBlock.end.label, padStart = 5)
                    printer.append(' ')
                    printLabel(tryCatchBlock.handler.label, padStart = 7)
                    printer.append("  ")
                    val type = tryCatchBlock.type
                    if (type == null) {
                        printer.append("any")
                    } else {
                        printer.append("Class ").appendJavaName(
                                Type.getObjectType(type),
                                // this might be a duplicate of a local var or it might not be. whatever
                                BindingRefType.LOCAL_VARIABLE_TYPE)
                    }
                    printer.append('\n')
                }
            }
        }

        private fun printSlotType(type: Any) {
            when (type) {
                is String -> printer.appendJavaName(Type.getObjectType(type), BindingRefType.LOCAL_VARIABLE_TYPE)
                is Label -> {
                    printer.append("new ")
                    printLabel(type)
                }
                Opcodes.TOP -> printer.append("top")
                Opcodes.INTEGER -> printer.append("int")
                Opcodes.FLOAT -> printer.append("float")
                Opcodes.LONG -> printer.append("long")
                Opcodes.DOUBLE -> printer.append("double")
                Opcodes.NULL -> printer.append("null")
                Opcodes.UNINITIALIZED_THIS -> printer.append("uninitialized-this")
                else -> throw IllegalArgumentException("Unknown slot type: $type")
            }
        }

        override fun visitFrame(type: Int, numLocal: Int, local: Array<Any>?, numStack: Int, stack: Array<Any>?) {
            printer.indent(3)
            printer.append("StackMap locals:")
            if (local != null) {
                for (slotType in local) {
                    printer.append(' ')
                    printSlotType(slotType)
                }
            }
            printer.append('\n')
            printer.indent(3)
            printer.append("StackMap stack:")
            if (stack != null) {
                for (slotType in stack) {
                    printer.append(' ')
                    printSlotType(slotType)
                }
            }
            printer.append('\n')
        }

        override fun visitLineNumber(line: Int, start: Label) {
            require(this.nextInstructionLabel == start)
            label()
            printer.annotate(Style("comment")) {
                printer.annotate(SourceLineRef(sourceFilePath, line)) {
                    printer.append(".line ").append(line).append('\n')
                }
            }
        }

        private fun printVariable(variable: LocalVariableNode, start: Boolean) {
            printer.indent(4)
            printer.append(if (start) "start" else "end").append(" local ")
            printer.annotate(getLocalVariableAnnotation(variable)) { printer.append(variable.index) }
            printer.annotate(Style("comment")) {
                printer.append(" // ")
                        .appendJavaName(Type.getType(variable.desc), BindingRefType.LOCAL_VARIABLE_TYPE, duplicate = true)
                        .append(' ')
                printer.annotate(getLocalVariableAnnotation(variable)) { printer.append(variable.name) }
                printer.append('\n')
            }
        }

        override fun visitAttribute(attribute: Attribute?) {
            throw UnsupportedOperationException()
        }

        private fun label() {
            printer.indent(2)
            val labelHere = nextInstructionLabel
            if (labelHere != null && !nextInstructionLabelPrinted) {
                printLabel(labelHere, padStart = 6)
                printer.append(": ")
                nextInstructionLabelPrinted = true
            } else {
                printer.append(" ".repeat(8))
            }
        }

        private inline fun insn(opcode: Int, f: () -> Unit) {
            label()
            nextInstructionLabel = null
            printer.annotate(Style("")) {
                printer.append(getMnemonic(opcode))
            }
            f()
            printer.append('\n')
        }

        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
                insn(Opcodes.MULTIANEWARRAY) {
                    // TYPE_PARAMETER is the ref type used in the source files for arrays too
                    printer.append(' ').appendDescriptor(Type.getType(descriptor), BindingRefType.TYPE_PARAMETER)
                    printer.append(' ').append(numDimensions)
                }

        override fun visitVarInsn(opcode: Int, `var`: Int) = insn(opcode) {
            printer.append(' ')
            val isStore = when (opcode) {
                Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.LSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> true
                else -> false
            }
            printLocalVariableWithOptionalName(`var`, storeOnly = isStore)
        }

        private fun switch(
                opcode: Int,
                comment: String,
                keys: IntArray,
                labels: Array<Label>,
                default: Label
        ) = insn(opcode) {
            require(keys.size == labels.size)
            printer.append(" { // ").append(comment).append('\n')
            for ((k, label) in keys.zip(labels)) {
                printer.indent(5)
                printer.append(String.format("%${MAX_INT_LENGTH}s", k)).append(": ")
                printLabel(label)
                printer.append('\n')
            }
            printer.indent(5)
            printer.append(String.format("%${MAX_INT_LENGTH}s", "default")).append(": ")
            printLabel(default)
            printer.append('\n')
            printer.indent(5)
            printer.append('}')
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<Label>) {
            switch(Opcodes.LOOKUPSWITCH, comment = "${keys.size}", keys = keys, labels = labels, default = dflt)
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Array<Label>) {
            val keys = IntArray(max - min + 1) { min + it }
            switch(Opcodes.TABLESWITCH, comment = "$min - $max", keys = keys, labels = labels, default = dflt)
        }

        override fun visitJumpInsn(opcode: Int, label: Label) = insn(opcode) {
            printer.append(" ")
            printLabel(label)
        }

        override fun visitLdcInsn(value: Any) = insn(Opcodes.LDC) {
            printer.append(' ').appendConstant(value)
        }

        override fun visitIntInsn(opcode: Int, operand: Int) = insn(opcode) {
            printer.append(' ').append(operand)
        }

        override fun visitTypeInsn(opcode: Int, type: String) = insn(opcode) {
            printer.append(' ').appendJavaName(Type.getObjectType(type), when (opcode) {
                Opcodes.NEW -> BindingRefType.CONSTRUCTOR_CALL
                Opcodes.ANEWARRAY -> BindingRefType.TYPE_PARAMETER
                Opcodes.INSTANCEOF -> BindingRefType.INSTANCE_OF
                Opcodes.CHECKCAST -> BindingRefType.CAST
                else -> throw AssertionError()
            })
        }

        override fun visitInvokeDynamicInsn(name: String,
                                            descriptor: String,
                                            bootstrapMethodHandle: Handle,
                                            bootstrapMethodArguments: Array<Any>) = insn(Opcodes.INVOKEDYNAMIC) {
            // The semantics of this name and type depend on the invoked bootstrap method. We can't say generally that
            // the two refer to anything special, so we can't link to anything better than the descriptor here.
            printer.append(' ').append(name).appendDescriptor(Type.getMethodType(descriptor), BindingRefType.INDY_TYPE)
                    .append('\n')
            val tag = when (bootstrapMethodHandle.tag) {
                Opcodes.H_GETFIELD -> "getfield"
                Opcodes.H_GETSTATIC -> "getstatic"
                Opcodes.H_PUTFIELD -> "putfield"
                Opcodes.H_PUTSTATIC -> "putstatic"
                Opcodes.H_INVOKEVIRTUAL -> "invokevirtual"
                Opcodes.H_INVOKESTATIC -> "invokestatic"
                Opcodes.H_INVOKESPECIAL -> "invokespecial"
                Opcodes.H_NEWINVOKESPECIAL -> "newinvokespecial"
                Opcodes.H_INVOKEINTERFACE -> "invokeinterface"
                else -> throw UnsupportedOperationException("Unsupported indy tag: ${bootstrapMethodHandle.tag}")
            }
            printer.indent(7)
            printer.append("Bootstrap: ").append(tag).append(' ')
                    .appendMember(
                            Type.getObjectType(bootstrapMethodHandle.owner),
                            bootstrapMethodHandle.name,
                            Type.getType(bootstrapMethodHandle.desc),
                            BindingRefType.INDY_BOOTSTRAP
                    )
                    .append('\n')
            printer.indent(8)
            printer.append("Method arguments:")
            for (argument in bootstrapMethodArguments) {
                printer.append('\n')
                printer.indent(9)
                printer.appendConstant(argument)
            }
        }

        override fun visitLabel(label: Label) {
            for (lv in (node.localVariables ?: emptyList()).asReversed()) {
                if (lv.end.label == label) {
                    printVariable(lv, start = false)
                }
            }
            currentLabel = label
            nextInstructionLabel = label
            nextInstructionLabelPrinted = false
            for (lv in node.localVariables ?: emptyList()) {
                if (lv.start.label == label) {
                    printVariable(lv, start = true)
                }
            }
        }

        override fun visitMethodInsn(opcode: Int,
                                     owner: String,
                                     name: String,
                                     descriptor: String,
                                     isInterface: Boolean) = insn(opcode) {
            printer.append(' ')
                    .appendMember(Type.getObjectType(owner), name, Type.getType(descriptor), BindingRefType.METHOD_CALL)
        }

        override fun visitInsn(opcode: Int) = insn(opcode) {}

        override fun visitInsnAnnotation(typeRef: Int,
                                         typePath: TypePath?,
                                         descriptor: String,
                                         visible: Boolean): AnnotationVisitor? {
            // javac shows these in the normal top-level RuntimeVisibleTypeAnnotations, but with asm we have no way
            // of actually showing which instruction this annotation is linked to.
            printer.indent(7)
            printer.append(if (visible) "RuntimeVisibleTypeAnnotation: " else "RuntimeInvisibleTypeAnnotation: ")
            return FullAnnotationPrinter(printer, descriptor, trailingNewLine = true)
        }

        override fun visitIincInsn(`var`: Int, increment: Int) = insn(Opcodes.IINC) {
            printer.append(' ')
            printLocalVariableWithOptionalName(`var`)
            printer.append(' ').append(increment)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) = insn(opcode) {
            printer.append(' ').appendMember(Type.getObjectType(owner), name, Type.getType(descriptor), when (opcode) {
                Opcodes.GETSTATIC, Opcodes.GETFIELD -> BindingRefType.FIELD_READ
                Opcodes.PUTSTATIC, Opcodes.PUTFIELD -> BindingRefType.FIELD_WRITE
                else -> throw AssertionError()
            })
        }

        /**
         * @param storeOnly If this variable is used in a store, it may only be defined immediately after this instruction.
         */
        private fun findScopeVariable(index: Int, storeOnly: Boolean = false): LocalVariableNode? {
            val next = currentInstruction.next
            val currentLabelIndex = if (storeOnly && next is LabelNode) {
                labels.indexOf(next.label)
            } else {
                // might be -1
                labels.indexOf(currentLabel)
            }
            // we almost always have a local variable, but there are exceptions like the throwable in a finally block.
            return node.localVariables.singleOrNull {
                val startLabelIndex = labels.indexOf(it.start.label)
                val endLabelIndex = labels.indexOf(it.end.label)
                val inRange = currentLabelIndex in (startLabelIndex until endLabelIndex)
                it.index == index && inRange
            }
        }

        private fun printLocalVariableWithOptionalName(index: Int, storeOnly: Boolean = false) {
            val lv = findScopeVariable(index, storeOnly)
            if (lv != null) {
                printLocalVariable(lv)
            } else {
                // bail :(
                printer.append(index)
            }
        }
    }
}