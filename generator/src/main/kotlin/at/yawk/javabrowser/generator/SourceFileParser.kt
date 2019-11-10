package at.yawk.javabrowser.generator

import at.yawk.javabrowser.Tokenizer
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FileASTRequestor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors


/**
 * @author yawkat
 */
class SourceFileParser(
        private val sourceRoot: Path,
        private val printer: Printer
) {
    var dependencies = emptyList<Path>()
    var includeRunningVmBootclasspath = true
    var pathPrefix = ""

    fun compile() {
        val parser = ASTParser.newParser(AST.JLS10)
        parser.setCompilerOptions(mapOf(
                JavaCore.COMPILER_SOURCE to JavaCore.VERSION_10,
                JavaCore.CORE_ENCODING to "UTF-8",
                JavaCore.COMPILER_DOC_COMMENT_SUPPORT to JavaCore.ENABLED
        ))
        parser.setResolveBindings(true)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setEnvironment(
                dependencies.map { it.toString() }.toTypedArray(),
                arrayOf(sourceRoot.toString()),
                arrayOf("UTF-8"),
                includeRunningVmBootclasspath)


        val files = Files.walk(sourceRoot)
                .filter { it.toString().endsWith(".java") && !Files.isDirectory(it) }
                .collect(Collectors.toList())

        parser.createASTs(
                files.map { it.toString() }.toTypedArray(),
                files.map { "UTF-8" }.toTypedArray(),
                emptyArray<String>(),
                Requestor(sourceRoot, printer, pathPrefix),
                null
        )
    }

    private class Requestor(val root: Path, val printer: Printer, val pathPrefix: String) : FileASTRequestor() {
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            try {
                accept0(sourceFilePath, ast)
            } catch (e: Exception) {
                throw RuntimeException("Failed to accept $sourceFilePath", e)
            }
        }

        private fun accept0(sourceFilePath: String, ast: CompilationUnit) {
            val relativePath = pathPrefix + root.relativize(Paths.get(sourceFilePath))

            val text = Files.readAllBytes(Paths.get(sourceFilePath)).toString(Charsets.UTF_8)
            val annotatedSourceFile = GeneratorSourceFile(text)
            val styleVisitor = StyleVisitor(annotatedSourceFile)
            for (comment in ast.commentList) {
                (comment as ASTNode).accept(styleVisitor)
            }
            ast.accept(styleVisitor)
            val sourceFileType = when {
                sourceFilePath.endsWith("package-info.java") -> BindingVisitor.SourceFileType.PACKAGE_INFO
                sourceFilePath.endsWith("module-info.java") -> BindingVisitor.SourceFileType.MODULE_INFO
                else -> BindingVisitor.SourceFileType.REGULAR
            }
            val bindingVisitor = BindingVisitor(sourceFileType, ast, annotatedSourceFile)
            try {
                ast.accept(bindingVisitor)
            } catch (e: Exception) {
                throw RuntimeException("Failed to accept node on character ${bindingVisitor.lastVisited?.startPosition}", e)
            }
            KeywordHandler.annotateKeywords(annotatedSourceFile,
                    styleVisitor.noKeywordRanges)

            annotatedSourceFile.bake()

            val tokens = Tokenizer.tokenize(text).toList()

            printer.addSourceFile(relativePath, annotatedSourceFile, tokens)
        }
    }
}
