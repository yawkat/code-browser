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
import org.eclipse.jdt.core.dom.Modifier
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
class SourceFileParser(
        private val sourceRoot: Path,
        val printer: Printer = Printer()
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
                SourceFileParser.Requestor(sourceRoot, printer, pathPrefix),
                null
        )
    }

    private class Requestor(val root: Path, val printer: Printer, val pathPrefix: String) : FileASTRequestor() {
        override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
            val relativePath = pathPrefix + root.relativize(Paths.get(sourceFilePath))

            val annotatedSourceFile = AnnotatedSourceFile(Files.readAllBytes(Paths.get(sourceFilePath))
                    .toString(Charsets.UTF_8))
            val styleVisitor = StyleVisitor(annotatedSourceFile)
            for (comment in ast.commentList) {
                (comment as ASTNode).accept(styleVisitor)
            }
            ast.accept(styleVisitor)
            ast.accept(object : ASTVisitor(true) {
                var refIdCounter = 0

                private fun makeBindingRef(type: BindingRefType, s: kotlin.String): BindingRef {
                    return BindingRef(type, s, refIdCounter++)
                }

                override fun visit(node: TypeDeclaration) = visitTypeDecl(node)

                override fun visit(node: AnnotationTypeDeclaration) = visitTypeDecl(node)

                override fun visit(node: EnumDeclaration) = visitTypeDecl(node)

                private fun visitTypeDecl(node: AbstractTypeDeclaration): Boolean {
                    val resolved = node.resolveBinding()
                    val binding = Bindings.toString(resolved)
                    if (binding != null) {
                        printer.registerType(binding)
                        annotatedSourceFile.annotate(node.name, BindingDecl(binding))
                    }
                    val superclass = resolved.superclass?.let { Bindings.toString(it) }
                    if (superclass != null) {
                        annotatedSourceFile.annotate(node.name, makeBindingRef(BindingRefType.SUPER_TYPE, superclass))
                    }
                    return true
                }

                override fun visit(node: MethodDeclaration): Boolean {
                    val resolved = node.resolveBinding()
                    if (resolved != null) {
                        val binding = Bindings.toString(resolved)
                        if (binding != null) {
                            annotatedSourceFile.annotate(node.name, BindingDecl(binding))
                        }
                        annotateOverrides(node.name, resolved)
                    }
                    return true
                }

                private fun annotateOverrides(targetNode: ASTNode, method: IMethodBinding) {
                    if (method.isConstructor || Modifier.isStatic(method.modifiers)) return

                    fun visit(typeBinding: ITypeBinding?) {
                        if (typeBinding == null) return

                        for (candidate in typeBinding.declaredMethods) {
                            if (method.overrides(candidate)) {
                                val ref = Bindings.toString(candidate)
                                if (ref != null) {
                                    annotatedSourceFile.annotate(targetNode,
                                            makeBindingRef(BindingRefType.SUPER_TYPE, ref))
                                }
                            }
                        }
                    }

                    fun visitSupers(typeBinding: ITypeBinding) {
                        visit(typeBinding.superclass)
                        typeBinding.interfaces.forEach { visit(it) }
                    }

                    visitSupers(method.declaringClass ?: return)
                }

                override fun visit(node: VariableDeclarationFragment): Boolean {
                    val resolved = node.resolveBinding()
                    if (resolved != null && resolved.isField) {
                        val binding = Bindings.toString(resolved)
                        if (binding != null) {
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
                        if (s != null) annotatedSourceFile.annotate(node,
                                makeBindingRef(BindingRefType.UNCLASSIFIED, s))
                    }
                    return true
                }

                override fun visit(node: MethodInvocation): Boolean {
                    val binding = node.resolveMethodBinding()
                    if (binding != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.METHOD_CALL, s))
                    }
                    return true
                }

                override fun visit(node: SuperMethodInvocation): Boolean {
                    val binding = node.resolveMethodBinding()
                    if (binding != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.SUPER_METHOD_CALL, s))
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
                        if (s != null) {
                            annotatedSourceFile.annotate(superKeywordStart, "super".length,
                                    makeBindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL, s))
                        }
                    }
                    return true
                }

                override fun visit(node: FieldAccess): Boolean {
                    val binding = node.resolveFieldBinding()
                    if (binding != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.FIELD_ACCESS, s))
                    }
                    return true
                }

                override fun visit(node: SuperFieldAccess): Boolean {
                    val binding = node.resolveFieldBinding()
                    if (binding != null) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.FIELD_ACCESS, s))
                    }
                    return true
                }

                override fun visit(node: SimpleName): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IVariableBinding) {
                        if (binding.isField) {
                            val s = Bindings.toString(binding)
                            if (s != null) annotatedSourceFile.annotate(node,
                                    makeBindingRef(BindingRefType.FIELD_ACCESS, s))
                        } else { // local
                            val id = Long.toHexString(Hashing.goodFastHash(64)
                                    .hashString(binding.key, Charsets.UTF_8).asLong())
                            annotatedSourceFile.annotate(node, LocalVariableRef(id))
                        }
                    }
                    if (binding is ITypeBinding) {
                        if (node.parent !is AbstractTypeDeclaration &&
                                node.parent !is Type) {
                            val s = Bindings.toString(binding)
                            if (s != null) annotatedSourceFile.annotate(node,
                                    makeBindingRef(BindingRefType.UNCLASSIFIED, s))
                        }
                    }
                    return true
                }

                override fun visit(node: MethodRef): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IMethodBinding) {
                        val s = Bindings.toString(binding)
                        if (s != null) {
                            annotatedSourceFile.annotate(node.name, makeBindingRef(BindingRefType.JAVADOC, s))
                        }
                    }
                    return true
                }

                override fun visit(node: MemberRef): Boolean {
                    val binding = node.resolveBinding()
                    if (binding is IMethodBinding) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.JAVADOC, s))
                    } else if (binding is IVariableBinding && binding.isField) {
                        val s = Bindings.toString(binding)
                        if (s != null) annotatedSourceFile.annotate(node.name,
                                makeBindingRef(BindingRefType.JAVADOC, s))
                    }
                    return true
                }
            })
            KeywordHandler.annotateKeywords(annotatedSourceFile, styleVisitor.noKeywordRanges)

            annotatedSourceFile.bake()
            printer.addSourceFile(relativePath, annotatedSourceFile)
        }
    }
}
