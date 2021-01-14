package at.yawk.javabrowser.generator

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.bytecode.BytecodePrinter
import at.yawk.javabrowser.generator.bytecode.ClassPrinter
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FileASTRequestor
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration
import org.eclipse.jdt.internal.compiler.parser.PrepareMonkeyPatch
import org.objectweb.asm.ClassReader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

private val log = LoggerFactory.getLogger(SourceFileParser::class.java)

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
    var outputClassesTo: Path? = null

    /**
     * For logging
     */
    var artifactId: String? = null
    var printBytecode: Boolean = false

    init {
        PrepareMonkeyPatch
    }

    suspend fun compile() {
        val parser = ASTParser.newParser(AST.JLS10)
        parser.setCompilerOptions(
            mapOf(
                JavaCore.COMPILER_SOURCE to JavaCore.VERSION_10,
                JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM to JavaCore.VERSION_10,
                JavaCore.CORE_ENCODING to "UTF-8",
                JavaCore.COMPILER_DOC_COMMENT_SUPPORT to JavaCore.ENABLED,
                JavaCore.COMPILER_LOCAL_VARIABLE_ATTR to JavaCore.GENERATE,
                JavaCore.COMPILER_LINE_NUMBER_ATTR to JavaCore.GENERATE,
                JavaCore.COMPILER_CODEGEN_METHOD_PARAMETERS_ATTR to JavaCore.GENERATE
        ))
        parser.setResolveBindings(true)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setEnvironment(
                dependencies.map { it.toString() }.toTypedArray(),
                // source dir arrays. Attention: When these are set, the source bytecode is compiled *without* our
                // compiler options.
                emptyArray(), emptyArray(),
                includeRunningVmBootclasspath)


        val files = Files.walk(sourceRoot)
                .filter { it.toString().endsWith(".java") && !Files.isDirectory(it) }
                .collect(Collectors.toList())

        printer.concurrencyControl.runParser(files.size) {
            log.info("Compiling $sourceRoot with dependencies $dependencies")

            val requestor = Requestor()
            parser.createASTs(
                    files.map { it.toString() }.toTypedArray(),
                    files.map { "UTF-8" }.toTypedArray(),
                    emptyArray<String>(),
                    requestor,
                    null
            )
        }
    }

    private inner class Requestor : FileASTRequestor() {
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            try {
                accept0(sourceFilePath, ast)
            } catch (e: Exception) {
                throw RuntimeException("Failed to accept $sourceFilePath", e)
            }
        }

        private fun accept0(sourceFilePath: String, ast: CompilationUnit) {
            val sourceRelativePath = pathPrefix + sourceRoot.relativize(Paths.get(sourceFilePath))

            val outputClassesTo = outputClassesTo
            if (outputClassesTo != null || printBytecode) {
                val internalDeclaration = unsafeGetInternalNode(ast) as CompilationUnitDeclaration
                for (classFile in internalDeclaration.compilationResult.classFiles) {
                    val classRelativePath = String(classFile.fileName()) + ".class"
                    if (outputClassesTo != null) {
                        val target = outputClassesTo.resolve(classRelativePath).normalize()
                        if (!target.startsWith(outputClassesTo)) throw AssertionError("Bad class file name")
                        try {
                            Files.createDirectories(target.parent)
                        } catch (ignored: FileAlreadyExistsException) {
                        }
                        Files.write(target, classFile.bytes)
                    }
                    if (printBytecode) {
                        val bytecodePrinter = BytecodePrinter(printer::hashBinding)
                        val reader = ClassReader(classFile.bytes)
                        ClassPrinter.accept(bytecodePrinter, sourceRelativePath, reader)
                        printer.addSourceFile(
                                pathPrefix + classRelativePath,
                                sourceFile = bytecodePrinter.finish(),
                                tokens = emptyList(), // TODO
                                realm = Realm.BYTECODE
                        )
                    }
                }
            }

            val text = Files.readAllBytes(Paths.get(sourceFilePath)).toString(Charsets.UTF_8)
            val annotatedSourceFile = GeneratorSourceFile(ast.`package`?.name?.fullyQualifiedName, text)
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
            val bindingVisitor = BindingVisitor(
                sourceFilePath, sourceFileType, ast, annotatedSourceFile, printer::hashBinding
            )
            try {
                ast.accept(bindingVisitor)
            } catch (e: Exception) {
                throw RuntimeException(
                    "Failed to accept node on character ${bindingVisitor.lastVisited?.startPosition}",
                    e
                )
            }
            KeywordHandler.annotateKeywords(
                annotatedSourceFile,
                styleVisitor.noKeywordRanges,
                sourceFileType
            )
            val javadocRenderVisitor = JavadocRenderVisitor(printer::hashBinding, annotatedSourceFile)
            try {
                ast.accept(javadocRenderVisitor)
            } catch (e: Exception) {
                throw RuntimeException(
                    "Failed to accept javadoc on character ${javadocRenderVisitor.lastVisited?.startPosition}",
                    e
                )
            }
            javadocRenderVisitor.finish()

            annotatedSourceFile.bake()

            val tokens = Tokenizer.tokenize(text).toList()

            printer.addSourceFile(sourceRelativePath, annotatedSourceFile, tokens, Realm.SOURCE)
        }
    }
}
