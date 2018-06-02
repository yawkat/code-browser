package at.yawk.javabrowser

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.*
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
                JavaCore.CORE_ENCODING to "UTF-8",
                JavaCore.COMPILER_DOC_COMMENT_SUPPORT to JavaCore.ENABLED
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

    class Requestor(val root: Path, val printer: Printer) : FileASTRequestor() {
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            val relativePath = root.relativize(Paths.get(sourceFilePath)).toString()

            val annotatedSourceFile = AnnotatedSourceFile(Files.readAllBytes(Paths.get(sourceFilePath))
                    .toString(Charsets.UTF_8))
            val styleVisitor = StyleVisitor(annotatedSourceFile)
            for (comment in ast.commentList) {
                (comment as ASTNode).accept(styleVisitor)
            }
            ast.accept(styleVisitor)
            ast.accept(object : ASTVisitor(true) {
                override fun visit(node: TypeDeclaration) = visitTypeDecl(node)

                override fun visit(node: AnnotationTypeDeclaration) = visitTypeDecl(node)

                override fun visit(node: EnumDeclaration) = visitTypeDecl(node)

                private fun visitTypeDecl(node: AbstractTypeDeclaration): Boolean {
                    val binding = Bindings.toString(node.resolveBinding())
                    if (binding != null) {
                        printer.registerBinding(binding, relativePath)
                        printer.registerType(binding)
                        annotatedSourceFile.annotate(node.name, BindingDecl(binding))
                        annotatedSourceFile.annotate(node.name, BindingRef(binding))
                    }
                    return true
                }

                override fun visit(node: MethodDeclaration): Boolean {
                    val resolved = node.resolveBinding()
                    if (resolved != null) {
                        val binding = Bindings.toString(resolved)
                        if (binding != null) {
                            printer.registerBinding(binding, relativePath)
                            annotatedSourceFile.annotate(node.name, BindingDecl(binding))
                            annotatedSourceFile.annotate(node.name, BindingRef(binding))
                        }
                    }
                    return true
                }

                override fun visit(node: VariableDeclarationFragment): Boolean {
                    val resolved = node.resolveBinding()
                    if (resolved != null && resolved.isField) {
                        val binding = Bindings.toString(resolved)
                        if (binding != null) {
                            printer.registerBinding(binding, relativePath)
                            annotatedSourceFile.annotate(node.name, BindingDecl(binding))
                        }
                    }
                    return true
                }

                override fun visit(node: NameQualifiedType) = visitType(node)

                override fun visit(node: SimpleType) = visitType(node)

                private fun visitType(node: Type): Boolean {
                    val binding = node.resolveBinding()
                    if (binding != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: MethodInvocation): Boolean {
                    val binding = node.resolveMethodBinding()
                    if (binding != null && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: FieldAccess): Boolean {
                    val binding = node.resolveFieldBinding()
                    if (binding != null && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: SimpleName): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IVariableBinding && binding.isField && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: MethodRef): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IMethodBinding) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: MemberRef): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IMethodBinding) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    } else if (binding is IVariableBinding && binding.isField && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }
            })
            KeywordHandler.annotateKeywords(annotatedSourceFile, styleVisitor.noKeywordRanges)

            printer.addSourceFile(relativePath, annotatedSourceFile)
        }
    }
}
