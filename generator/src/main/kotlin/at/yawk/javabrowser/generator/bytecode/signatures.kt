package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.Style
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.util.TraceSignatureVisitor

private class SignatureOutput(
        val printer: BytecodePrinter,
        val access: Int
) {
    val stringer = TraceSignatureVisitor(access)
    private var flushed = 0

    fun flush(removeSuffix: String = "") {
        val declaration = stringer.declaration
        require(declaration.endsWith(removeSuffix))
        printer.append(declaration.substring(flushed, declaration.length - removeSuffix.length))
        flushed = declaration.length
    }

    fun pos(): Int {
        // faster to just flush immediately instead of only looking at the declaration...
        flush()
        return printer.position()
    }
}

/**
 * This visitor inserts annotations at just the right places. It is very dependent on the implementation of
 * [TraceSignatureVisitor] and will need adjustment if generics ever change.
 */
private open class AnnotatingVisitor(
        val output: SignatureOutput,
        val delegate: SignatureVisitor = output.stringer,
        private val flushOnEnd: Boolean = true
) : SignatureVisitor(Opcodes.ASM8) {
    override fun visitBaseType(descriptor: Char) {
        // annotate the primitive type as a keyword
        val start = output.pos()
        val primitiveNameLength = Type.getType(descriptor.toString()).className.length
        output.printer.annotate(start, primitiveNameLength, Style("keyword"))

        delegate.visitBaseType(descriptor)
    }

    override fun visitClassType(name: String) {
        delegate.visitClassType(name)
        val modified = name.replace('/', '.')
        if (output.stringer.declaration.endsWith(modified)) {
            // todo: annotate
        }
    }

    override fun visitInnerClassType(name: String) {
        // no way I'm resolving that by hand
        delegate.visitInnerClassType(name)
    }

    override fun visitParameterType(): SignatureVisitor {
        return AnnotatingVisitor(output, super.visitParameterType())
    }

    override fun visitFormalTypeParameter(name: String) {
        delegate.visitFormalTypeParameter(name)
    }

    override fun visitClassBound(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitClassBound())
    }

    override fun visitInterface(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitInterface())
    }

    override fun visitTypeVariable(name: String) {
        delegate.visitTypeVariable(name)
    }

    override fun visitExceptionType(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitExceptionType())
    }

    override fun visitInterfaceBound(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitInterfaceBound())
    }

    override fun visitArrayType(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitArrayType())
    }

    override fun visitEnd() {
        delegate.visitEnd()
        if (flushOnEnd) {
            output.flush()
        }
    }

    override fun visitSuperclass(): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitSuperclass())
    }

    override fun visitTypeArgument() {
        delegate.visitTypeArgument()
    }

    override fun visitTypeArgument(wildcard: Char): SignatureVisitor {
        return AnnotatingVisitor(output, delegate.visitTypeArgument(wildcard))
    }

    override fun visitReturnType(): SignatureVisitor {
        return AnnotatingVisitor(output, super.visitReturnType())
    }
}

fun BytecodePrinter.printJavaSignature(signature: String, access: Int = 0, isTypeSignature: Boolean) {
    val output = SignatureOutput(this, access)
    val visitor = AnnotatingVisitor(output)
    val reader = SignatureReader(signature)
    if (isTypeSignature) {
        reader.acceptType(visitor)
    } else {
        reader.accept(visitor)
    }
    output.flush()
}

private object NoopVisitor : SignatureVisitor(Opcodes.ASM8)

fun BytecodePrinter.printMethodSignature(signature: String, access: Int, name: String) {
    // yes this method is hacky as shit but unfortunately TraceSignatureVisitor is not flexible enough to let us insert
    // the method name in the right place

    // input: <E:Ljava/lang/Exception;>(Ljava/util/List<TE;>;I)Ljava/util/List<Ljava/lang/String;>;^TE;^Ljava/lang/Throwable;
    // output: <E extends java.lang.Exception> java.util.List<java.lang.String> x(java.util.List<E> l, int i) throws E, java.lang.Throwable
    val reader = SignatureReader(signature)

    // first pass: type param declaration
    val firstOutput = SignatureOutput(this, access)
    val firstStart = firstOutput.pos()
    val firstPass = object : AnnotatingVisitor(firstOutput, flushOnEnd = false) {
        override fun visitParameterType() = NoopVisitor
        override fun visitReturnType(): SignatureVisitor {
            delegate.visitReturnType() // do nothing with the return, just finish the type param declaration.
            return NoopVisitor
        }

        override fun visitExceptionType() = NoopVisitor
    }
    reader.accept(firstPass)
    firstPass.visitEnd()
    firstOutput.flush(removeSuffix = "()") // visitEnd inserts superfluous parentheses for 0 parameters
    if (firstOutput.pos() != firstStart) {
        append(' ')
    }

    // second pass: return type
    val secondPass = object : SignatureVisitor(Opcodes.ASM8) {
        val retVisitor = AnnotatingVisitor(SignatureOutput(this@printMethodSignature, 0))

        override fun visitReturnType() = retVisitor
        override fun visitEnd() = retVisitor.visitEnd()
    }
    reader.accept(secondPass)
    secondPass.visitEnd()

    append(' ').append(name).append('(')

    // third pass: param type
    val thirdPass = object : SignatureVisitor(Opcodes.ASM8) {
        var lastItem: SignatureVisitor? = null

        override fun visitParameterType(): SignatureVisitor {
            lastItem?.visitEnd()
            if (lastItem != null) append(", ")
            val visitor = AnnotatingVisitor(SignatureOutput(this@printMethodSignature, 0))
            lastItem = visitor
            return visitor
        }

        override fun visitEnd() {
            lastItem?.visitEnd()
        }
    }
    reader.accept(thirdPass)
    append(')')

    // fourth pass: exception type
    val fourthPass = object : SignatureVisitor(Opcodes.ASM8) {
        var lastItem: SignatureVisitor? = null

        override fun visitExceptionType(): SignatureVisitor {
            lastItem?.visitEnd()
            append(if (lastItem != null) ", " else " throws ")
            val visitor = AnnotatingVisitor(SignatureOutput(this@printMethodSignature, 0))
            lastItem = visitor
            return visitor
        }

        override fun visitEnd() {
            lastItem?.visitEnd()
        }
    }
    reader.accept(fourthPass)
    fourthPass.visitEnd()
}