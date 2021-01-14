package at.yawk.javabrowser.generator

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.RenderedJavadoc
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration
import org.eclipse.jdt.core.dom.BodyDeclaration
import org.eclipse.jdt.core.dom.EnumConstantDeclaration
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.VariableDeclaration
import org.jsoup.nodes.Document

class JavadocRenderVisitor(
    private val hashBinding: String.() -> BindingId,
    private val annotatedSourceFile: GeneratorSourceFile
) : ASTVisitor() {

    internal var lastVisited: ASTNode? = null

    private val outstanding = HashSet<Javadoc>()
    private val done = HashSet<Javadoc>()

    override fun visit(node: Javadoc): Boolean {
        outstanding.add(node)
        return false
    }

    override fun preVisit(node: ASTNode) {
        lastVisited = node

        if (node !is BodyDeclaration) return
        val javadoc = node.javadoc ?: return
        val declaringClass = if (node is AbstractTypeDeclaration) {
            node.resolveBinding()
        } else {
            (node.parent as? AbstractTypeDeclaration)?.resolveBinding()
        }
        val binding = when (node) {
            is AbstractTypeDeclaration -> node.resolveBinding()
            is AnnotationTypeMemberDeclaration -> node.resolveBinding()
            is EnumConstantDeclaration -> node.resolveVariable()
            is FieldDeclaration -> (node.fragments().singleOrNull() as? VariableDeclaration)?.resolveBinding()
            is MethodDeclaration -> node.resolveBinding()
            else -> null
        }

        done.add(javadoc)
        apply(
            javadoc, JavadocRenderer(
                hashBinding,
                declaringClassQualifiedName = declaringClass?.qualifiedName,
                subjectBinding = binding
            )
        )
    }

    fun finish() {
        outstanding.removeAll(done)
        outstanding.forEach { apply(it, JavadocRenderer(hashBinding)) }
    }

    private fun apply(javadoc: Javadoc, context: JavadocRenderer) {
        val renderedNodes = context.render(javadoc)
        val shell = Document.createShell("")
        shell.outputSettings().prettyPrint(false)
        shell.body().insertChildren(0, renderedNodes)
        val html = shell.body().html()
        annotatedSourceFile.annotate(javadoc, RenderedJavadoc(html))
    }
}