package at.yawk.javabrowser.generator.source

import at.yawk.javabrowser.generator.work.TempDirProviderTest
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Files

class ModuleFileParserTest {
    @Test
    fun test() {
        TempDirProviderTest.withTempDirSync("ModuleFileParserTest") { tmp ->
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