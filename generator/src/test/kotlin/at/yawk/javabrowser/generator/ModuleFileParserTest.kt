package at.yawk.javabrowser.generator

import at.yawk.javabrowser.generator.artifact.tempDir
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Files

class ModuleFileParserTest {
    @Test
    fun test() {
        tempDir { tmp ->
            val moduleInfo = tmp.resolve("module-info.java")
            Files.write(moduleInfo, """
module foo {
    requires ab.bar;
}                
            """.toByteArray())
            val moduleDeclaration = parseModuleFile(moduleInfo)
            Assert.assertEquals(getRequiredModules(moduleDeclaration), setOf("ab.bar"))
        }
    }
}