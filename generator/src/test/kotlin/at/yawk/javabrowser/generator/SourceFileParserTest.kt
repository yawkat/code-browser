package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.LocalVariableRef
import at.yawk.javabrowser.SourceAnnotation
import com.google.common.io.MoreFiles
import org.hamcrest.BaseMatcher
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.intellij.lang.annotations.Language
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * @author yawkat
 */
@Suppress("RemoveRedundantBackticks")
class SourceFileParserTest {
    private var tmp = Paths.get("/null")

    @BeforeMethod
    fun setup() {
        tmp = Files.createTempDirectory("SourceFileParserTest")
    }

    @AfterMethod
    fun tearDown() {
        MoreFiles.deleteRecursively(tmp)
    }

    private val src: Path
        get() = tmp.resolve("src")

    private fun write(path: String, @Language("Java") content: String) {
        val p = src.resolve(path)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray())
    }

    private fun annotate(code: String,
                         annotation: SourceAnnotation,
                         word: String,
                         index: Int = 0): AnnotatedSourceFile.Entry {
        var i = index
        var j = -1
        while (i-- >= 0) {
            j = code.indexOf(word, j + 1)
            if (j == -1) throw NoSuchElementException()
        }

        return AnnotatedSourceFile.Entry(j, word.length, annotation)
    }

    private fun compile(): Map<String, AnnotatedSourceFile> {
        val printer = Printer.SimplePrinter()
        val parser = SourceFileParser(src, printer)
        parser.compile()
        return printer.sourceFiles
    }

    private fun compileOne() = compile().values.single()

    @Test
    fun superMethodCall() {
        val a = "class A { public int hashCode() { return super.hashCode(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(a,
                        BindingRef(BindingRefType.SUPER_METHOD_CALL,
                                "java.lang.Object#hashCode()",
                                3), "hashCode", 1))
        )
    }

    @Test
    fun superConstructorCall() {
        val a = "class A extends B { public A() { super(); } }"
        write("A.java", a)
        write("B.java", "class B { public B() {} }")
        MatcherAssert.assertThat(
                compile()["A.java"]!!.entries,
                Matchers.hasItem(annotate(a,
                        BindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL,
                                "B()",
                                1), "super"))
        )
    }

    @Test
    fun superConstructorCallImplicit() {
        val a = "class A { public A() { super(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(
                        a,
                        BindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL,
                                "java.lang.Object()",
                                1),
                        "super"
                ))
        )
    }

    @Test
    fun staticCall() {
        val a = "class A { static void x() { A.x(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(a,
                        BindingRef(BindingRefType.STATIC_MEMBER_QUALIFIER,
                                "A",
                                3), "A", 1))
        )
    }

    @Test
    fun local() {
        val a = "class A { { int variable; variable++; } }"
        write("A.java", a)
        val entries = compileOne().entries
        val lvr = entries.find { it.annotation is LocalVariableRef }!!.annotation
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(annotate(a, lvr, "variable"))
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(annotate(a, lvr, "variable", 1))
        )
    }

    @Test
    fun genericCall() {
        val a = "class A { { x(3); } static <T> T x(T t) { return t; } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(
                        a,
                        BindingRef(BindingRefType.METHOD_CALL,
                                "A#x(java.lang.Object)",
                                1),
                        "x",
                        0
                ))
        )
    }

    @Test
    fun `ref - type constraint`() {
        write("A.java", "class A<T extends A> {}")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.TYPE_CONSTRAINT
                })
        )
    }

    @Test
    fun `ref - type constraint on method`() {
        write("A.java", "class A { <T extends A> void x() {} }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.TYPE_CONSTRAINT
                })
        )
    }

    @Test
    fun `ref - instanceof type`() {
        write("A.java", "class A { { if (A.class instanceof A) {} } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.INSTANCE_OF
                })
        )
    }

    @Test
    fun `ref - cast type`() {
        write("A.java", "class A { { System.out.println((A) A.class); } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CAST
                })
        )
    }

    @Test
    fun `ref - import`() {
        write("A.java", "import java.lang.String; class A {}")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.IMPORT &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `ref - import static`() {
        write("A.java", "import static java.lang.String.valueOf; class A {}")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.IMPORT &&
                            annotation.binding == "java.lang.String#valueOf(char[],int,int)"
                })
        )
    }

    @Test
    fun `ref - import static wildcard`() {
        write("A.java", "import static java.lang.String.*; class A {}")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.IMPORT &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `ref - annotation type`() {
        write("A.java", "class A { @Override public int hashCode() { return 5; } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.ANNOTATION_TYPE &&
                            annotation.binding == "java.lang.Override"
                })
        )
    }

    @Test
    fun `ref - field type`() {
        write("A.java", "class A { A a; }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.FIELD_TYPE &&
                            annotation.binding == "A"
                })
        )
    }

    @Test
    fun `ref - this constructor call`() {
        write("A.java", "class A { A() {}  A(String s) { this(); } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CONSTRUCTOR_CALL &&
                            annotation.binding == "A()"
                })
        )
    }

    @Test
    fun `ref - this constructor call with param and comment`() {
        write("A.java", "class A { <T> A() {}  A(String s) { <String> /* this */ this(); } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CONSTRUCTOR_CALL &&
                            annotation.binding == "A()"
                })
        )
    }

    @Test
    fun `ref - new instance`() {
        write("A.java", "class A { { new A(); } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CONSTRUCTOR_CALL &&
                            annotation.binding == "A"
                })
        )
    }

    @Test
    fun `ref - throws`() {
        write("A.java", "class A { void x() throws Exception {} }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.THROWS_DECLARATION &&
                            annotation.binding == "java.lang.Exception"
                })
        )
    }

    @Test
    fun `ref - static receiver`() {
        write("A.java", "class A { { String.valueOf(5); } }")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.STATIC_MEMBER_QUALIFIER &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `ref - javadoc`() {
        write("A.java", "/** {@link java.util.concurrent.atomic.AtomicIntegerArray#compareAndSet(int, int, int)}  */ class A {}")
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.JAVADOC &&
                            annotation.binding == "java.util.concurrent.atomic.AtomicIntegerArray#compareAndSet(int,int,int)"
                })
        )
    }

    @Test
    fun `ref - var`() {
        write("A.java", "class A {{ var b = new B(); }}")
        write("B.java", "class B {}")
        MatcherAssert.assertThat(
                compile()["A.java"]!!.entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.LOCAL_VARIABLE_TYPE &&
                            annotation.binding == "B"
                })
        )
    }

    @Test
    fun `ref - lambda`() {
        write("A.java", "class A {{ B b = () -> {}; }}")
        write("B.java", "interface B extends Runnable {}")
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.SUPER_METHOD &&
                            annotation.binding == "java.lang.Runnable#run()" &&
                            it.start == 20 && it.length == 2
                })
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.SUPER_TYPE &&
                            annotation.binding == "B"
                })
        )
    }

    @Test
    fun `ref - lambda parameter implicit`() {
        write("A.java", "class A {{ java.util.function.Consumer<B> r = b -> {}; }}")
        write("B.java", "class B {}")
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.PARAMETER_TYPE &&
                            annotation.binding == "B"
                })
        )
    }

    @Test
    fun `ref - lambda parameter explicit`() {
        write("A.java", "class A {{ java.util.function.Consumer<B> r = (B b) -> {}; }}")
        write("B.java", "class B {}")
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.PARAMETER_TYPE &&
                            annotation.binding == "B"
                })
        )
    }

    @Test
    fun `ref - method ref`() {
        write("A.java", "class A {{ B b = B::test; }}")
        write("B.java", "interface B extends Runnable { static void test() {} }")
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.SUPER_METHOD &&
                            annotation.binding == "java.lang.Runnable#run()" &&
                            // should annotate the ::
                            it.start == 18 && it.length == 2
                })
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.METHOD_CALL &&
                            annotation.binding == "B#test()" &&
                            // should annotate the test
                            it.start == 20 && it.length == 4
                })
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.SUPER_TYPE &&
                            annotation.binding == "B" &&
                            // should annotate nothing
                            it.length == 0
                })
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.METHOD_REFERENCE_RECEIVER_TYPE &&
                            annotation.binding == "B" &&
                            // should annotate the receiver
                            it.start == 17 && it.length == 1
                })
        )
    }

    @Test
    fun `anonymous class constructor`() {
        write("A.java", "class A {{ new B() {}; }}")
        write("B.java", "class B {}") // no explicit constructor
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CONSTRUCTOR_CALL &&
                            annotation.binding == "B"
                })
        )
    }

    @Test
    fun `anonymous class constructor - param`() {
        write("A.java", "class A {{ new B(\"abc\") {}; }}")
        write("B.java", "class B { B(String s) {} }")
        val entries = compile()["A.java"]!!.entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.CONSTRUCTOR_CALL &&
                            annotation.binding == "B(java.lang.String)"
                })
        )
    }

    @Test
    fun `type parameter`() {
        write("A.java", "import java.util.List; class A { List<String> list; }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.TYPE_PARAMETER &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `wildcard parameter`() {
        write("A.java", "import java.util.List; class A { List<? extends String> list; }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.WILDCARD_BOUND &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `array type`() {
        write("A.java", "import java.util.List; class A { String[] list; }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.TYPE_PARAMETER &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `class expression`() {
        write("A.java", "class A { Class<?> c = String.class; }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.STATIC_MEMBER_QUALIFIER &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `static method call isn't unclassified`() {
        write("A.java", "class A {{ String.format(\"x\"); }}")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.not(Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.UNCLASSIFIED
                }))
        )
    }

    @Test
    fun `static field access`() {
        write("A.java", "class A {{ Object o = System.out; }}")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.STATIC_MEMBER_QUALIFIER &&
                            annotation.binding == "java.lang.System"
                })
        )
    }

    @Test
    fun `super field access`() {
        write("A.java", "class A { Object object; }")
        write("B.java", "class B extends A {{ object = super.object; }}")
        val entries = compile().getValue("B.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.FIELD_ACCESS &&
                            annotation.binding == "A#object"
                })
        )
    }

    @Test
    fun `method ref to constructor`() {
        write("A.java", "import java.util.function.Consumer; class A { Consumer<String> c = String::new; }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.METHOD_REFERENCE_RECEIVER_TYPE &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `annotation field type`() {
        write("A.java", "@interface A { String value(); }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.RETURN_TYPE &&
                            annotation.binding == "java.lang.String"
                })
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.not(Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.UNCLASSIFIED
                }))
        )
    }

    @Test
    fun `method invocation type param`() {
        write("A.java", "class A {{this.<String>x();} <T> void x() {}}")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.TYPE_PARAMETER &&
                            annotation.binding == "java.lang.String"
                })
        )
    }

    @Test
    fun `this reference`() {
        write("A.java", "class A {{A.this.hashCode();}}")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.THIS_REFERENCE_QUALIFIER &&
                            annotation.binding == "A"
                })
        )
    }

    @Test
    fun `super reference`() {
        write("A.java", "class A {{Object.super.hashCode();}}")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.SUPER_REFERENCE_QUALIFIER &&
                            annotation.binding == "java.lang.Object"
                })
        )
    }

    @Test
    fun `annotation value`() {
        write("A.java", "@A(x = \"\") @interface A { String x(); }")
        val entries = compile().getValue("A.java").entries
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(matches<AnnotatedSourceFile.Entry> {
                    val annotation = it.annotation
                    annotation is BindingRef && annotation.type == BindingRefType.ANNOTATION_MEMBER_VALUE &&
                            annotation.binding == "A#x()"
                })
        )
    }

    private inline fun <reified T> matches(crossinline pred: (T) -> Boolean): Matcher<T> = object : BaseMatcher<T>() {
        override fun describeTo(description: Description) {
            description.appendText("Matches lambda")
        }

        override fun matches(item: Any?) = item is T && pred(item)
    }
}