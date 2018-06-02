package at.yawk.javabrowser

import com.google.common.hash.Hashing
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FileASTRequestor
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MemberRef
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.MethodRef
import org.eclipse.jdt.core.dom.NameQualifiedType
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.SuperConstructorInvocation
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import java.lang.Long
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors


/**
 * @author yawkat
 */
object SourceFileParser {

    fun compile(
            sourceRoot: Path,
            dependencies: List<Path>,
            includeRunningVmBootclasspath: Boolean = true
    ): Printer {
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
                .filter { it.toString().endsWith(".java") }
                .collect(Collectors.toList())

        val printer = Printer()

        parser.createASTs(
                files.map { it.toString() }.toTypedArray(),
                files.map { "UTF-8" }.toTypedArray(),
                emptyArray<String>(),
                SourceFileParser.Requestor(sourceRoot, printer),
                null
        )

        return printer
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

                override fun visit(node: SuperMethodInvocation): Boolean {
                    val binding = node.resolveMethodBinding()
                    if (binding != null && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: SuperConstructorInvocation): Boolean {
                    val superKeywordStart =
                            if (node.expression == null) node.startPosition
                            else node.expression.startPosition + node.expression.length + 1
                    val binding = node.resolveConstructorBinding()
                    if (binding != null && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(superKeywordStart, "super".length, BindingRef(s))
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

                override fun visit(node: SuperFieldAccess): Boolean {
                    val binding = node.resolveFieldBinding()
                    if (binding != null && binding.declaringClass != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name, BindingRef(s))
                    }
                    return true
                }

                override fun visit(node: SimpleName): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IVariableBinding) {
                        if (binding.isField) {
                            if (binding.declaringClass != null) {
                                val s = Bindings.toString(binding)
                                if (s != null) annotatedSourceFile.annotate(node, BindingRef(s))
                            }
                        } else { // local
                            val id = Long.toHexString(Hashing.goodFastHash(64)
                                    .hashString(binding.key, Charsets.UTF_8).asLong())
                            annotatedSourceFile.annotate(node, LocalVariableRef(id))
                        }
                    }
                    if (binding is ITypeBinding) {
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
