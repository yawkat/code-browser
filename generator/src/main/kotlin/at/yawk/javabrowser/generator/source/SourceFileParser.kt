package at.yawk.javabrowser.generator.source

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.JavadocRenderVisitor
import at.yawk.javabrowser.generator.KeywordHandler
import at.yawk.javabrowser.generator.SourceSetConfig
import at.yawk.javabrowser.generator.bytecode.BytecodePrinter
import at.yawk.javabrowser.generator.bytecode.ClassPrinter
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.runBlocking
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
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val log = LoggerFactory.getLogger(SourceFileParser::class.java)

/**
 * @author yawkat
 */
class SourceFileParser(
    private val printer: Printer,
    private val config: SourceSetConfig
) {
    var acceptContext: CoroutineContext = EmptyCoroutineContext

    var withSourceFilePermits: suspend (Int, suspend () -> Unit) -> Unit = { _, f -> f() }

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
                JavaCore.COMPILER_CODEGEN_METHOD_PARAMETERS_ATTR to JavaCore.GENERATE,
                JavaCore.COMPILER_COMPLIANCE to
                        // java.base contains @PolymorphicSignature methods. JDT doesn't like compiling those, so fall
                        // back to 1.6, which doesn't allow them.
                        if (config.quirkIsJavaBase) JavaCore.VERSION_1_6
                        else JavaCore.VERSION_10
            )
        )
        parser.setResolveBindings(true)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setEnvironment(
            config.dependencies.map { it.toString() }.toTypedArray(),
            // source dir arrays. Attention: When these are set, the source bytecode is compiled *without* our
            // compiler options.
            emptyArray(), emptyArray(),
            config.includeRunningVmBootclasspath
        )


        @Suppress("BlockingMethodInNonBlockingContext")
        val files = Files.walk(config.sourceRoot)
            .filter { it.toString().endsWith(".java") && !Files.isDirectory(it) }
            .collect(Collectors.toList())

        if (files.isEmpty()) throw Exception("No source files for ${config.debugTag} (${config.sourceRoot})")

        withSourceFilePermits(files.size) {
            log.info("Compiling ${config.debugTag} (${config.sourceRoot}) with dependencies ${config.dependencies}")

            try {
                val requestor = Requestor()
                parser.createASTs(
                    files.map { it.toString() }.toTypedArray(),
                    files.map { "UTF-8" }.toTypedArray(),
                    emptyArray<String>(),
                    requestor,
                    null
                )
            } catch (e: Exception) {
                throw RuntimeException("Failed to compile ${config.debugTag}", e)
            }
        }
    }

    private inner class Requestor : FileASTRequestor() {
        @ObsoleteCoroutinesApi
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            try {
                runBlocking(acceptContext) {
                    accept0(sourceFilePath, ast)
                }
            } catch (e: Exception) {
                throw RuntimeException("Failed to accept $sourceFilePath", e)
            }
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        private suspend fun accept0(sourceFilePath: String, ast: CompilationUnit) {
            val sourceRelativePath = config.pathPrefix + config.sourceRoot.relativize(Paths.get(sourceFilePath))

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

            val outputClassesTo = config.outputClassesTo
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
                val sourceFileRefs = annotatedSourceFile.entries
                    .map { it.annotation }
                    .filterIsInstance<BindingDecl>()
                    .filter { it.corresponding.containsKey(Realm.BYTECODE) }
                    .associate { it.corresponding.getValue(Realm.BYTECODE) to it.id }

                val bytecodePrinter = BytecodePrinter(printer::hashBinding, sourceFileRefs)
                val reader = ClassReader(classFile.bytes)
                ClassPrinter.accept(bytecodePrinter, sourceRelativePath, reader)
                printer.addSourceFile(
                    config.pathPrefix + classRelativePath,
                    sourceFile = bytecodePrinter.finish(),
                    tokens = emptyList(), // TODO
                    realm = Realm.BYTECODE
                )
            }
        }
    }
}
