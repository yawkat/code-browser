package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.Style
import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.util.TraceSignatureVisitor
import org.skife.jdbi.asm.Type

fun BytecodePrinter.printJavaSignature(signature: String, access: Int = 0, isTypeSignature: Boolean) {
    val stringer = TraceSignatureVisitor(access)

    fun pos() = position() + stringer.declaration.length

    /**
     * This visitor inserts annotations at just the right places. It is very dependent on the implementation of
     * [TraceSignatureVisitor] and will need adjustment if generics ever change.
     */
    class AnnotatingVisitor(val delegate: SignatureVisitor) : SignatureVisitor(Opcodes.ASM8) {
        override fun visitBaseType(descriptor: Char) {
            // annotate the primitive type as a keyword
            val start = pos()
            val primitiveNameLength = Type.getType(descriptor.toString()).className.length
            annotate(start, primitiveNameLength, Style("keyword"))

            delegate.visitBaseType(descriptor)
        }

        override fun visitClassType(name: String) {
            delegate.visitClassType(name)
            val modified = name.replace('/', '.')
            if (stringer.declaration.endsWith(modified)) {
                // todo: annotate
            }
        }

        override fun visitInnerClassType(name: String) {
            // no way I'm resolving that by hand
            delegate.visitInnerClassType(name)
        }

        override fun visitParameterType(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitParameterType())
        }

        override fun visitFormalTypeParameter(name: String) {
            delegate.visitFormalTypeParameter(name)
        }

        override fun visitClassBound(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitClassBound())
        }

        override fun visitInterface(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitInterface())
        }

        override fun visitTypeVariable(name: String) {
            delegate.visitTypeVariable(name)
        }

        override fun visitExceptionType(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitExceptionType())
        }

        override fun visitInterfaceBound(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitInterfaceBound())
        }

        override fun visitArrayType(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitArrayType())
        }

        override fun visitEnd() {
            delegate.visitEnd()
        }

        override fun visitSuperclass(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitSuperclass())
        }

        override fun visitTypeArgument() {
            delegate.visitTypeArgument()
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitTypeArgument(wildcard))
        }

        override fun visitReturnType(): SignatureVisitor {
            return AnnotatingVisitor(delegate.visitReturnType())
        }
    }

    val reader = SignatureReader(signature)
    if (isTypeSignature) {
        reader.acceptType(AnnotatingVisitor(stringer))
    } else {
        reader.accept(AnnotatingVisitor(stringer))
    }

    append(stringer.declaration)
}