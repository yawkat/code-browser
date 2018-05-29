package at.yawk.javabrowser

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FileASTRequestor
import org.eclipse.jdt.core.dom.NameQualifiedType
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeDeclaration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors


/**
 * @author yawkat
 */
class SourceFileParser(private val path: Path) {

    fun parse(printer: Printer) {
        val parser = ASTParser.newParser(AST.JLS10)
        parser.setCompilerOptions(mapOf(
                JavaCore.COMPILER_SOURCE to JavaCore.VERSION_10,
                JavaCore.CORE_ENCODING to "UTF-8"
        ))
        parser.setResolveBindings(true)
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setEnvironment(emptyArray<String>(), arrayOf(path.toString()), arrayOf("UTF-8"), true)


        val files = Files.walk(path)
                .filter { it.toString().endsWith(".java") }
                .collect(Collectors.toList())

        parser.createASTs(
                files.map { it.toString() }.toTypedArray(),
                files.map { "UTF-8" }.toTypedArray(),
                emptyArray<String>(),
                Requestor(path, printer),
                null
        )
    }

    private class Requestor(val root: Path, val printer: Printer) : FileASTRequestor() {
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            val relativePath = root.relativize(Paths.get(sourceFilePath)).toString()

            val annotatedSourceFile = AnnotatedSourceFile(Files.readAllBytes(Paths.get(sourceFilePath))
                    .toString(Charsets.UTF_8))
            ast.accept(object : Visitor(annotatedSourceFile) {
                override fun visit(node: TypeDeclaration): Boolean {
                    printer.registerType(node.resolveBinding().binaryName, relativePath)
                    return super.visit(node)
                }

                override fun visit(node: AnnotationTypeDeclaration): Boolean {
                    printer.registerType(node.resolveBinding().binaryName, relativePath)
                    return super.visit(node)
                }

                override fun visit(node: EnumDeclaration): Boolean {
                    printer.registerType(node.resolveBinding().binaryName, relativePath)
                    return super.visit(node)
                }
            })

            printer.addSourceFile(relativePath, annotatedSourceFile)
        }
    }

    private open class Visitor(val annotatedSourceFile: AnnotatedSourceFile) : ASTVisitor() {
        override fun visit(node: NameQualifiedType) = visitType(node)

        override fun visit(node: SimpleType) = visitType(node)

        private fun visitType(node: Type): Boolean {
            val binding = node.resolveBinding()
            if (binding != null) annotatedSourceFile.annotate(node, TypeRef(binding.binaryName))
            return true
        }
    }
}
