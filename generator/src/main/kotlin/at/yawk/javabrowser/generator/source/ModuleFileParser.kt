package at.yawk.javabrowser.generator.source

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FileASTRequestor
import org.eclipse.jdt.core.dom.ModuleDeclaration
import org.eclipse.jdt.core.dom.RequiresDirective
import org.eclipse.jdt.internal.compiler.parser.PrepareMonkeyPatch
import java.nio.file.Path

fun parseModuleFile(path: Path): ModuleDeclaration {
    PrepareMonkeyPatch

    var moduleDeclaration: ModuleDeclaration? = null
    val parser = ASTParser.newParser(AST.JLS_Latest)
    parser.setCompilerOptions(mapOf(
            JavaCore.COMPILER_SOURCE to JavaCore.latestSupportedJavaVersion(),
            JavaCore.CORE_ENCODING to "UTF-8"
    ))
    parser.setKind(ASTParser.K_COMPILATION_UNIT)
    parser.createASTs(
            arrayOf(path.toAbsolutePath().toString()),
            arrayOf("UTF-8"),
            emptyArray<String>(),
            object : FileASTRequestor() {
                override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
                    moduleDeclaration = ast.module
                }
            },
            null
    )
    return moduleDeclaration ?: throw IllegalArgumentException("Not a module file: $path")
}

fun getRequiredModules(declaration: ModuleDeclaration): Set<String> {
    return declaration.moduleStatements()
            .filterIsInstance<RequiresDirective>()
            .map { it.name.fullyQualifiedName }
            .toSet()
}