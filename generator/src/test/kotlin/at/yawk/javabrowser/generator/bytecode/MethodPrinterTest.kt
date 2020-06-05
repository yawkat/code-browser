package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.Printer
import at.yawk.javabrowser.generator.SourceFileParser
import com.google.common.io.MoreFiles
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Files

class MethodPrinterTest {
    private fun getMethodOutput(
            @Language("java") code: String,
            interestMethod: String
    ): String {
        lateinit var output: BytecodePrinter

        val tmp = Files.createTempDirectory("MethodPrinterTest")
        try {
            Files.write(tmp.resolve("Main.java"), code.toByteArray())

            val out = tmp.resolve("out")
            Files.createDirectory(out)
            val sourceFileParser = SourceFileParser(tmp, object : Printer {
                override fun addSourceFile(path: String,
                                           sourceFile: GeneratorSourceFile,
                                           tokens: List<Tokenizer.Token>) {
                }
            })
            sourceFileParser.outputClassesTo = out
            runBlocking {
                sourceFileParser.compile()
            }
            Files.newDirectoryStream(out).use { classes ->
                for (cl in classes) {
                    val classReader = ClassReader(Files.readAllBytes(cl))
                    classReader.accept(object : ClassVisitor(Opcodes.ASM8) {
                        override fun visitMethod(access: Int,
                                                 name: String?,
                                                 descriptor: String,
                                                 signature: String?,
                                                 exceptions: Array<String>?): MethodVisitor? {
                            if (name == interestMethod) {
                                output = BytecodePrinter()
                                return MethodPrinter.visitor(
                                        output,
                                        access,
                                        name,
                                        descriptor,
                                        signature,
                                        exceptions
                                )
                            }
                            return null
                        }
                    }, 0)
                }
            }
        } finally {
            @Suppress("UnstableApiUsage")
            MoreFiles.deleteRecursively(tmp)
        }

        return output.finishString().trimIndent()
    }

    @Test
    fun flags() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                public static synchronized void x() {
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
public static synchronized void x();
  descriptor: ()V
  flags: (0x0029) ACC_PUBLIC, ACC_STATIC, ACC_SYNCHRONIZED
  Code:
    stack=0, locals=0, args_size=0
       0: .line 4
          return
    LocalVariableTable:
      Start  End  Slot  Name  Signature
""".trimIndent()
        )
    }

    @Test
    fun field() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                int a;
                                
                                void x() {
                                    a = 1;
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=2, locals=1, args_size=1
     start local 0 // A this
      0: .line 6
         aload 0
         iconst_1
         putfield A.a:I
      1: .line 7
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    2     0  this  LA;
                """.trimIndent()
        )
    }

    @Test
    fun inc() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                int x() {
                                    int a = 1;
                                    a++;
                                    a--;
                                    return a;
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
int x();
 descriptor: ()I
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_1
         istore 1
     start local 1 // int a
      1: .line 5
         iinc 1 (a) 1
      2: .line 6
         iinc 1 (a) -1
      3: .line 7
         iload 1
         ireturn
     end local 1 // int a
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    4     0  this  LA;
         1    4     1     a  I
                """.trimIndent()
        )
    }

    @Test
    fun multiNewArray() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    Object o = new int[1][2];
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=2, locals=1, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_1
         iconst_2
         multianewarray [[I 2
         pop
      1: .line 5
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    2     0  this  LA;
""".trimIndent()
        )
    }

    @Test
    fun switch() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    int a = 0;
                                    switch (a) {
                                        case 0: break;
                                        case 1: break;
                                        case 2: break;
                                        case 3: break;
                                    }
                                    switch (a) {
                                        case 0: break;
                                        case 123: break;
                                    }
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_0
         istore 1
     start local 1 // int a
      1: .line 5
         iload 1
         tableswitch { // 0 - 3
                 0: 2
                 1: 3
                 2: 4
                 3: 5
           default: 5
       }
      2: .line 6
   StackMap locals: int
   StackMap stack:
         goto 5
      3: .line 7
   StackMap locals:
   StackMap stack:
         goto 5
      4: .line 8
   StackMap locals:
   StackMap stack:
         goto 5
      5: .line 11
   StackMap locals:
   StackMap stack:
         iload 1
         lookupswitch { // 2
                 0: 6
               123: 7
           default: 7
       }
      6: .line 12
   StackMap locals:
   StackMap stack:
         goto 7
      7: .line 15
   StackMap locals:
   StackMap stack:
         return
     end local 1 // int a
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    8     0  this  LA;
         1    8     1     a  I
""".trimIndent()
        )
    }

    @Test
    fun parameters() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x(int i, final String stuvwxyz) {
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x(int, java.lang.String);
 descriptor: (ILjava/lang/String;)V
 flags: (0x0000) 
 Code:
   stack=0, locals=3, args_size=3
     start local 0 // A this
     start local 1 // int i
     start local 2 // java.lang.String stuvwxyz
      0: .line 4
         return
     end local 2 // java.lang.String stuvwxyz
     end local 1 // int i
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot      Name  Signature
         0    1     0      this  LA;
         0    1     1         i  I
         0    1     2  stuvwxyz  Ljava/lang/String;
 MethodParameters:
       Name  Flags
   i         
   stuvwxyz  final
""".trimIndent()
        )
    }

    @Test
    fun exceptions() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() throws Exception {
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=0, locals=1, args_size=1
     start local 0 // A this
      0: .line 4
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    1     0  this  LA;
 Exceptions:
   throws java.lang.Exception
""".trimIndent()
        )
    }

    @Test
    fun genericSignature() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            import java.util.List;
                            class A {
                                <T extends List<? super String>> void x() {
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=0, locals=1, args_size=1
     start local 0 // A this
      0: .line 5
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    1     0  this  LA;
 Signature: <T::Ljava/util/List<-Ljava/lang/String;>;>()V
""".trimIndent()
        )
    }

    @Test
    fun annDefault() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            @interface A {
                                int x() default 5;
                            }
                        """,
                        interestMethod = "x"
                ),
                """
public abstract int x();
  descriptor: ()I
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
  AnnotationDefault: 5
""".trimIndent()
        )
    }

    @Test
    fun simpleAnnotation() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            import java.lang.annotation.*;
                            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @interface Visible {}
                            @Target(ElementType.METHOD) @interface Invisible {}
                            class A {
                                @Visible
                                @Invisible
                                void x() {
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=0, locals=1, args_size=1
     start local 0 // A this
      0: .line 9
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    1     0  this  LA;
 RuntimeVisibleAnnotations: 
   Visible()
 RuntimeInvisibleAnnotations: 
   Invisible()
""".trimIndent()
        )
    }

    @Test
    fun typeAnnotation() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            import java.lang.annotation.*;
                            import java.util.List;
                            @Target(ElementType.TYPE_USE) @interface Ann { int value(); }
                            interface A {
                                @Ann(2) Map<? extends @Ann(3) String, @Ann(1) Object @Ann(0) []> x();
                            }
                        """,
                        interestMethod = "x"
                ),
                """
public abstract Map x();
  descriptor: ()LMap;
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
  Signature: ()LMap<+Ljava/lang/String;[Ljava/lang/Object;>;
  RuntimeInvisibleTypeAnnotations: 
    METHOD_RETURN
      Ann(value = 2)
    METHOD_RETURN, location=[TYPE_ARGUMENT(0), WILDCARD_BOUND]
      Ann(value = 3)
    METHOD_RETURN, location=[TYPE_ARGUMENT(1), ARRAY_ELEMENT]
      Ann(value = 1)
    METHOD_RETURN, location=[TYPE_ARGUMENT(1)]
      Ann(value = 0)
""".trimIndent()
        )
    }

    @Test
    fun `param annotation`() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            @interface Ann { int value(); }
                            interface A {
                                void x(int j, @Ann(0) int i);
                            }                  
                        """,
                        interestMethod = "x"
                ),
                """
public abstract void x(int, int);
  descriptor: (II)V
  flags: (0x0401) ACC_PUBLIC, ACC_ABSTRACT
  RuntimeInvisibleParameterAnnotations: 
    0:
    1:
      Ann(value = 0)
  MethodParameters:
    Name  Flags
    j     
    i     
""".trimIndent()
        )
    }

    @Test
    fun lvt() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            import java.util.List;
                            class A {
                                Object x() {
                                    List<String> list = null;
                                    return list;
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
java.lang.Object x();
 descriptor: ()Ljava/lang/Object;
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 5
         aconst_null
         astore 1
     start local 1 // java.util.List list
      1: .line 6
         aload 1
         areturn
     end local 1 // java.util.List list
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    2     0  this  LA;
         1    2     1  list  Ljava/util/List<Ljava/lang/String;>;
""".trimIndent()
        )
    }

    @Test
    fun stackMap() {
        Assert.assertEquals(
                getMethodOutput(
                        """
class A {
    A(int a, String b, float c, double d) {
        super();
        if (a != 0) {eat(a);}
    }
    void eat(int a) {}
}
                        """,
                        interestMethod = "<init>"
                ),
                """
void <init>(int, java.lang.String, float, double);
 descriptor: (ILjava/lang/String;FD)V
 flags: (0x0000) 
 Code:
   stack=2, locals=6, args_size=5
     start local 0 // A this
     start local 1 // int a
     start local 2 // java.lang.String b
     start local 3 // float c
     start local 4 // double d
      0: .line 4
         aload 0
         invokespecial Ljava/lang/Object;.<init>:()V
      1: .line 5
         iload 1
         ifeq 2
         aload 0
         iload 1
         invokevirtual LA;.eat:(I)V
      2: .line 6
   StackMap locals: A int java.lang.String float double
   StackMap stack:
         return
     end local 4 // double d
     end local 3 // float c
     end local 2 // java.lang.String b
     end local 1 // int a
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    3     0  this  LA;
         0    3     1     a  I
         0    3     2     b  Ljava/lang/String;
         0    3     3     c  F
         0    3     4     d  D
 MethodParameters:
   Name  Flags
   a     
   b     
   c     
   d     
""".trimIndent()
        )
    }

    @Test
    fun methodCall() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    int a = 0;
                                    y(a);
                                }
                                void y(int a) {}
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=2, locals=2, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_0
         istore 1
     start local 1 // int a
      1: .line 5
         aload 0
         iload 1
         invokevirtual LA;.y:(I)V
      2: .line 6
         return
     end local 1 // int a
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    3     0  this  LA;
         1    3     1     a  I
""".trimIndent()
        )
    }

    @Test
    fun instanceof() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    Object o = null;
                                    if (o instanceof String) {
                                        return;
                                    }
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 4
         aconst_null
         astore 1
     start local 1 // java.lang.Object o
      1: .line 5
         aload 1
         instanceof java.lang.String
         ifeq 3
      2: .line 6
         return
      3: .line 8
   StackMap locals: java.lang.Object
   StackMap stack:
         return
     end local 1 // java.lang.Object o
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    4     0  this  LA;
         1    4     1     o  Ljava/lang/Object;
""".trimIndent()
        )
    }

    @Test
    fun newarray() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    int[] arr = new int[0];
                                }
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=1, locals=1, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_0
         newarray 10
         pop
      1: .line 5
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    2     0  this  LA;
""".trimIndent()
        )
    }

    @Test
    fun ldc() {
        Assert.assertEquals(
                getMethodOutput(
                        """
                            class A {
                                void x() {
                                    String s = "abcdef\tä\n";
                                    int i = 12345;
                                    double d = 3.14;
                                    eat(s, i, d);
                                }
                                
                                void eat(String s, int i, double d) {}
                            }
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=5, locals=5, args_size=1
     start local 0 // A this
      0: .line 4
         ldc "abcdef\t\u00E4\n"
         astore 1
     start local 1 // java.lang.String s
      1: .line 5
         sipush 12345
         istore 2
     start local 2 // int i
      2: .line 6
         ldc 3.14
         dstore 3
     start local 3 // double d
      3: .line 7
         aload 0
         aload 1
         iload 2
         dload 3
         invokevirtual LA;.eat:(Ljava/lang/String;ID)V
      4: .line 8
         return
     end local 3 // double d
     end local 2 // int i
     end local 1 // java.lang.String s
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    5     0  this  LA;
         1    5     1     s  Ljava/lang/String;
         2    5     2     i  I
         3    5     3     d  D
""".trimIndent()
        )
    }

    @Test
    fun `try catch`() {
        Assert.assertEquals(
                getMethodOutput(
                        """
class A {
    void x() {
        try {
            throw new Exception();
        } catch (Exception e) {
            return;
        } finally {
            return;
        }
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=2, locals=1, args_size=1
     start local 0 // A this
      0: .line 5
         new java.lang.Exception
         dup
         invokespecial Ljava/lang/Exception;.<init>:()V
         athrow
      1: .line 6
   StackMap locals:
   StackMap stack: java.lang.Exception
         pop
      2: .line 7
         goto 4
      3: .line 8
   StackMap locals:
   StackMap stack: java.lang.Throwable
         pop
      4: .line 9
   StackMap locals:
   StackMap stack:
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    5     0  this  LA;
   Exception table:
     from    to  target  type
        0     1       1  Class java.lang.Exception
        0     3       3  any
""".trimIndent()
        )
    }

    @Test
    fun `try catch annotation`() {
        Assert.assertEquals(
                getMethodOutput(
                        """
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface Ann {}
class A {
    void x() {
        try {
            throw new Exception();
        } catch (@Ann Exception e) {
            return;
        }
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=2, locals=1, args_size=1
     start local 0 // A this
      0: .line 9
         new java.lang.Exception
         dup
         invokespecial Ljava/lang/Exception;.<init>:()V
         athrow
      1: .line 10
   StackMap locals:
   StackMap stack: java.lang.Exception
         pop
      2: .line 11
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    3     0  this  LA;
   Exception table:
     from    to  target  type
        0     1       1  Class java.lang.Exception
 RuntimeVisibleTypeAnnotations: 
   EXCEPTION_PARAMETER
     Ann()
""".trimIndent()
        )
    }

    @Test
    fun `local variable annotation`() {
        Assert.assertEquals(
                getMethodOutput(
                        """
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface Ann {}
class A {
    int x() {
        int i = 0;
        try {
            i++;
        } finally {
            @Ann int j = 0;
            i += j;
        }
        return i;
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
int x();
 descriptor: ()I
 flags: (0x0000) 
 Code:
   stack=2, locals=4, args_size=1
     start local 0 // A this
      0: .line 8
         iconst_0
         istore 1
     start local 1 // int i
      1: .line 10
         iinc 1 (i) 1
      2: .line 11
         goto 7
   StackMap locals: A int
   StackMap stack: java.lang.Throwable
      3: astore 2
      4: .line 12
         iconst_0
         istore 3
     start local 3 // int j
      5: .line 13
         iload 1
         iload 3
         iadd
         istore 1
     end local 3 // int j
      6: .line 14
         aload 2
         athrow
      7: .line 12
   StackMap locals:
   StackMap stack:
         iconst_0
         istore 3
     start local 3 // int j
      8: .line 13
         iload 1
         iload 3
         iadd
         istore 1
     end local 3 // int j
      9: .line 15
         iload 1
         ireturn
     end local 1 // int i
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0   10     0  this  LA;
         1   10     1     i  I
         5    6     3     j  I
         8    9     3     j  I
   Exception table:
     from    to  target  type
        1     3       3  any
 RuntimeVisibleTypeAnnotations: 
   LOCAL_VARIABLE, {start=5, end=6, index=3 (j); start=8, end=9, index=3 (j)}
     Ann()
""".trimIndent()
        )
    }

    @Test
    fun `instanceof annotation`() {
        Assert.assertEquals(
                getMethodOutput(
                        """
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface Ann {}
class A {
    void x() {
        Object o = null;
        if (o instanceof @Ann String) {
            return;
        }
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 8
         aconst_null
         astore 1
     start local 1 // java.lang.Object o
      1: .line 9
         aload 1
         instanceof java.lang.String
           RuntimeVisibleTypeAnnotation: Ann()
         ifeq 3
      2: .line 10
         return
      3: .line 12
   StackMap locals: java.lang.Object
   StackMap stack:
         return
     end local 1 // java.lang.Object o
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    4     0  this  LA;
         1    4     1     o  Ljava/lang/Object;
""".trimIndent()
        )
    }

    @Test
    fun indy() {
        Assert.assertEquals(
                getMethodOutput(
                        """
class A {
    void x() {
        int i = 0;
        Runnable r = () -> { System.out.println(i); };
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=1, locals=2, args_size=1
     start local 0 // A this
      0: .line 4
         iconst_0
         istore 1
     start local 1 // int i
      1: .line 5
         iload 1
         invokedynamic run(I)Ljava/lang/Runnable;
           Bootstrap: invokestatic java.lang.invoke.LambdaMetafactory.metafactory:(Ljava/lang/invoke/MethodHandles${'$'}Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
             Method arguments:
               ()V
               A.lambda${'$'}0(I)V (6)
               ()V
         pop
      2: .line 6
         return
     end local 1 // int i
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    3     0  this  LA;
         1    3     1     i  I
""".trimIndent()
        )
    }

    @Test
    fun simple() {
        Assert.assertEquals(
                getMethodOutput(
                        """
class A {
    void x() {
    }
}
                        """,
                        interestMethod = "x"
                ),
                """
void x();
 descriptor: ()V
 flags: (0x0000) 
 Code:
   stack=0, locals=1, args_size=1
     start local 0 // A this
      0: .line 4
         return
     end local 0 // A this
   LocalVariableTable:
     Start  End  Slot  Name  Signature
         0    1     0  this  LA;
""".trimIndent()
        )
    }
}