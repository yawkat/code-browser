package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
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
            val relativePath = pathPrefix + root.relativize(Paths.get(sourceFilePath))

            val annotatedSourceFile = AnnotatedSourceFile(Files.readAllBytes(Paths.get(
                    sourceFilePath))
                    .toString(Charsets.UTF_8))
            val styleVisitor = StyleVisitor(annotatedSourceFile)
            for (comment in ast.commentList) {
                (comment as ASTNode).accept(styleVisitor)
            }
            ast.accept(styleVisitor)
            ast.accept(BindingVisitor(ast, annotatedSourceFile))
            KeywordHandler.annotateKeywords(annotatedSourceFile,
                    styleVisitor.noKeywordRanges)

            annotatedSourceFile.bake()
            printer.addSourceFile(relativePath, annotatedSourceFile)
        }
    }
}
