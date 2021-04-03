package at.yawk.javabrowser.generator.bytecode

import org.intellij.lang.annotations.Language
import org.testng.Assert
import org.testng.annotations.Test

const val VERSION = 59

class ClassPrinterTest {
    private fun getClassOutput(
            @Language("java") code: String,
            interestClass: String
    ) = getOutput(code, filter = { it.endsWith("$interestClass.class") }) { printer, file ->
        ClassPrinter.accept(printer, "", file)
    }

    @Test
    fun simple() {
        Assert.assertEquals(getClassOutput(
                """
class A {
}
                """,
                "A"
        ), """
class A
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A
  super_class: java.lang.Object
{
  void <init>();
    descriptor: ()V
    flags: (0x0000) 
    Code:
      stack=1, locals=1, args_size=1
        start local 0 // A this
         0: .line 2
            aload 0 /* this */
            invokespecial java.lang.Object.<init>:()V
            return
        end local 0 // A this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA;
}
SourceFile: "Main.java"
        """.trimIndent())
    }

    @Test
    fun extends() {
        Assert.assertEquals(getClassOutput(
                """
import java.io.Serializable;
import java.util.Properties;
class A extends Properties implements Serializable {
}
                """,
                "A"
        ), """
class A extends java.util.Properties implements java.io.Serializable
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A
  super_class: java.util.Properties
{
  void <init>();
    descriptor: ()V
    flags: (0x0000) 
    Code:
      stack=1, locals=1, args_size=1
        start local 0 // A this
         0: .line 4
            aload 0 /* this */
            invokespecial java.util.Properties.<init>:()V
            return
        end local 0 // A this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA;
}
SourceFile: "Main.java"
        """.trimIndent())
    }

    @Test
    fun `extends interface`() {
        Assert.assertEquals(getClassOutput(
                """
import java.io.Serializable;
import java.util.Properties;
interface A extends Serializable {
}
                """,
                "A"
        ), """
interface A extends java.io.Serializable
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0600) ACC_INTERFACE, ACC_ABSTRACT
  this_class: A
  super_class: java.lang.Object
{
}
SourceFile: "Main.java"
        """.trimIndent())
    }

    @Test
    fun generic() {
        Assert.assertEquals(getClassOutput(
                """
import java.util.List;
interface A<E extends List<E>> extends List<E> {
}
                """,
                "A"
        ), """
interface A<E extends java.util.List<E>> extends java.util.List<E>
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0600) ACC_INTERFACE, ACC_ABSTRACT
  this_class: A
  super_class: java.lang.Object
{
}
Signature: <E::Ljava/util/List<TE;>;>Ljava/lang/Object;Ljava/util/List<TE;>;
SourceFile: "Main.java"
        """.trimIndent())
    }

    // TODO: at time of writing, jdt is too old for nestmates.

    @Test
    fun `inner host`() {
        Assert.assertEquals(getClassOutput(
                """
class A {
    class B {
    }
}
                """,
                "A"
        ), """
class A
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A
  super_class: java.lang.Object
{
  void <init>();
    descriptor: ()V
    flags: (0x0000) 
    Code:
      stack=1, locals=1, args_size=1
        start local 0 // A this
         0: .line 2
            aload 0 /* this */
            invokespecial java.lang.Object.<init>:()V
            return
        end local 0 // A this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA;
}
SourceFile: "Main.java"
NestMembers:
  A${'$'}B
InnerClasses:
  B = A${'$'}B of A
        """.trimIndent())
    }

    @Test
    fun `inner`() {
        Assert.assertEquals(getClassOutput(
                """
class A {
    class B {
    }
}
                """,
                "A\$B"
        ), """
class A${'$'}B
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A${'$'}B
  super_class: java.lang.Object
{
  final A this${'$'}0;
    descriptor: LA;
    flags: (0x1010) ACC_FINAL, ACC_SYNTHETIC

  void <init>(A);
    descriptor: (LA;)V
    flags: (0x0000) 
    Code:
      stack=2, locals=2, args_size=2
        start local 0 // A${'$'}B this
         0: .line 3
            aload 0 /* this */
            aload 1
            putfield A${'$'}B.this${'$'}0:LA;
            aload 0 /* this */
            invokespecial java.lang.Object.<init>:()V
            return
        end local 0 // A${'$'}B this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA${'$'}B;
    MethodParameters:
        Name  Flags
      this${'$'}0  final
}
SourceFile: "Main.java"
NestHost: A
InnerClasses:
  B = A${'$'}B of A
        """.trimIndent())
    }

    @Test
    fun `anon init block`() {
        Assert.assertEquals(getClassOutput(
                """
class A {
    static { new Object() {}; }
}
                """,
                "A\$1"
        ), """
class A${'$'}1
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A${'$'}1
  super_class: java.lang.Object
{
  void <init>();
    descriptor: ()V
    flags: (0x0000) 
    Code:
      stack=1, locals=1, args_size=1
        start local 0 // A${'$'}1 this
         0: .line 3
            aload 0 /* this */
            invokespecial java.lang.Object.<init>:()V
            return
        end local 0 // A${'$'}1 this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA${'$'}1;
}
SourceFile: "Main.java"
EnclosingMethod: A
NestHost: A
InnerClasses:
  A${'$'}1
        """.trimIndent())
    }

    @Test
    fun `anon in method`() {
        Assert.assertEquals(getClassOutput(
                """
class A {
    static void a(int i) { new Object() {}; }
}
                """,
                "A\$1"
        ), """
class A${'$'}1
  minor version: 0
  major version: $VERSION
  flags: flags: (0x0020) ACC_SUPER
  this_class: A${'$'}1
  super_class: java.lang.Object
{
  void <init>();
    descriptor: ()V
    flags: (0x0000) 
    Code:
      stack=1, locals=1, args_size=1
        start local 0 // A${'$'}1 this
         0: .line 3
            aload 0 /* this */
            invokespecial java.lang.Object.<init>:()V
            return
        end local 0 // A${'$'}1 this
      LocalVariableTable:
        Start  End  Slot  Name  Signature
            0    1     0  this  LA${'$'}1;
}
SourceFile: "Main.java"
EnclosingMethod: A.a:(I)V
NestHost: A
InnerClasses:
  A${'$'}1
        """.trimIndent())
    }
}