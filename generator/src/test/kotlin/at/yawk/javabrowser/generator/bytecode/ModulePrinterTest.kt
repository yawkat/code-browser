package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingId
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ModuleNode
import org.testng.Assert
import org.testng.annotations.Test

class ModulePrinterTest {
    // codegen doesn't work for module-info right now, so we build our own module nodes.

    private fun hashBinding(binding: String) = BindingId(binding.hashCode().toLong())

    @Test
    fun require() {
        val node = ModuleNode("foo.bar", 0, null)
        node.visitRequire("a.b", 0, null)
        node.visitRequire("c", Opcodes.ACC_SYNTHETIC, "version")
        node.visitEnd()

        Assert.assertEquals(
                BytecodePrinter(this::hashBinding).also { printModuleBody(it, node) }.finishString().trimIndent(),
                """
  requires a.b; // flags: (0x0000) 
  requires c@version; // flags: (0x1000) ACC_SYNTHETIC
                """.trimIndent()
        )
    }

    @Test
    fun exports() {
        val node = ModuleNode("foo.bar", 0, null)
        node.visitExport("a/b", 0, "mod1", "mod2")
        node.visitExport("c", 0, "mod1")
        node.visitExport("d", 0)
        node.visitEnd()

        Assert.assertEquals(
                BytecodePrinter(this::hashBinding).also { printModuleBody(it, node) }.finishString().trimIndent(),
                """
exports a.b to // flags: (0x0000) 
  mod1,
  mod2;
exports c to mod1; // flags: (0x0000) 
exports d; // flags: (0x0000) 
                """.trimIndent()
        )
    }

    @Test
    fun opens() {
        val node = ModuleNode("foo.bar", 0, null)
        node.visitOpen("a/b", 0, "mod1", "mod2")
        node.visitOpen("c", 0, "mod1")
        node.visitOpen("d", 0)
        node.visitEnd()

        Assert.assertEquals(
                BytecodePrinter(this::hashBinding).also { printModuleBody(it, node) }.finishString().trimIndent(),
                """
opens a.b to // flags: (0x0000) 
  mod1,
  mod2;
opens c to mod1; // flags: (0x0000) 
opens d; // flags: (0x0000) 
                """.trimIndent()
        )
    }

    @Test
    fun provides() {
        val node = ModuleNode("foo.bar", 0, null)
        node.visitProvide("s/t", "a/b", "c", "d")
        node.visitProvide("s/t", "a/b", "c")
        node.visitProvide("s/t", "a/b")
        node.visitEnd()

        Assert.assertEquals(
                BytecodePrinter(this::hashBinding).also { printModuleBody(it, node) }.finishString().trimIndent(),
                """
provides s.t with 
  a.b,
  c,
  d;
provides s.t with 
  a.b,
  c;
provides s.t with 
  a.b;
                """.trimIndent()
        )
    }

    @Test
    fun header() {
        val node = ModuleNode("foo.bar", 0, "vers")
        node.visitMainClass("a/b")
        node.visitPackage("a")
        node.visitPackage("b/c")
        node.visitEnd()

        Assert.assertEquals(
                BytecodePrinter(this::hashBinding).also { printModuleAttributes(it, node) }.finishString().trimIndent(),
                """
Module:
  Name: foo.bar
  Version: vers
  flags: (0x0000) 
  MainClass: a.b
  Packages:
    a
    b.c
                """.trimIndent()
        )
    }
}