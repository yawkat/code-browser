package at.yawk.javabrowser.generator.bytecode

import org.intellij.lang.annotations.Language
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldNode
import org.testng.Assert
import org.testng.annotations.Test

class FieldPrinterTest {
    private fun getFieldOutput(
            @Language("java") code: String,
            interestField: String
    ) = getOutput(code) { printer, file ->
        val visitor = object : ClassVisitor(Opcodes.ASM8) {
            lateinit var type: Type

            override fun visit(version: Int,
                               access: Int,
                               name: String,
                               signature: String?,
                               superName: String?,
                               interfaces: Array<out String>?) {
                type = Type.getObjectType(name)
            }

            override fun visitField(access: Int,
                                    name: String,
                                    descriptor: String,
                                    signature: String?,
                                    value: Any?): FieldVisitor? {
                if (name != interestField) return null
                return object : FieldNode(Opcodes.ASM8, access, name, descriptor, signature, value) {
                    override fun visitEnd() {
                        super.visitEnd()

                        printField(printer, type, this)
                    }
                }
            }
        }
        file.accept(visitor, 0)
    }

    @Test
    fun simple() {
        Assert.assertEquals(getFieldOutput(
                """
class A {
    int i;
}
                """,
                interestField = "i"
        ), """
int i;
  descriptor: I
  flags: (0x0000) 
        """.trimIndent())
    }

    @Test
    fun access() {
        Assert.assertEquals(getFieldOutput(
                """
class A {
    public transient volatile int i;
}
                """,
                interestField = "i"
        ), """
public volatile transient int i;
  descriptor: I
  flags: (0x00c1) ACC_PUBLIC, ACC_VOLATILE, ACC_TRANSIENT
        """.trimIndent())
    }

    @Test
    fun constant() {
        Assert.assertEquals(getFieldOutput(
                """
class A {
    final int i = 5;
}
                """,
                interestField = "i"
        ), """
final int i;
  descriptor: I
  flags: (0x0010) ACC_FINAL
  ConstantValue: 5
        """.trimIndent())
    }

    @Test
    fun generic() {
        Assert.assertEquals(getFieldOutput(
                """
import java.util.List;
class A {
    List<String> i;
}
                """,
                interestField = "i"
        ), """
java.util.List<java.lang.String> i;
  descriptor: Ljava/util/List;
  flags: (0x0000) 
  Signature: Ljava/util/List<Ljava/lang/String;>;
        """.trimIndent())
    }

    @Test
    fun annotation() {
        Assert.assertEquals(getFieldOutput(
                """
@interface Ann {}
class A {
    @Ann int i;
}
                """,
                interestField = "i"
        ), """
int i;
  descriptor: I
  flags: (0x0000) 
  RuntimeInvisibleAnnotations: 
    Ann()
        """.trimIndent())
    }

    @Test
    fun `type annotation`() {
        // simple test case because the same code is already well-tested in MethodPrinterTest
        Assert.assertEquals(getFieldOutput(
                """
import java.lang.annotation.*;
@Target(ElementType.TYPE_USE)
@interface Ann {}
class A {
    @Ann int i;
}
                """,
                interestField = "i"
        ), """
int i;
  descriptor: I
  flags: (0x0000) 
  RuntimeInvisibleTypeAnnotations: 
    FIELD
      Ann()
        """.trimIndent())
    }
}