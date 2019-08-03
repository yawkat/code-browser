package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import com.google.common.collect.Range
import com.google.common.collect.TreeRangeSet
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.BlockComment
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.LineComment
import org.eclipse.jdt.core.dom.MarkerAnnotation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NormalAnnotation
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.TagElement

fun AnnotatedSourceFile.annotate(node: ASTNode, annotation: SourceAnnotation) =
        annotate(node.startPosition, node.length, annotation)

/**
 * @author yawkat
 */
class StyleVisitor(private val annotatedSourceFile: AnnotatedSourceFile) : ASTVisitor(true) {
    val noKeywordRanges = TreeRangeSet.create<Int>()!!

    override fun visit(node: SimpleName): Boolean {
        val classes = HashSet<String>()
        if (node.isDeclaration) classes.add("declaration")
        val binding = node.resolveBinding()
        when (binding) {
            is IVariableBinding -> {
                classes.add(if (binding.isField) "field" else "variable")
                if (binding.isEffectivelyFinal) classes.add("effectively-final")
                if (Modifier.isFinal(binding.modifiers)) classes.add("final")
                if (Modifier.isStatic(binding.modifiers)) classes.add("static")
            }
            is ITypeBinding -> {
                if (binding.isTypeVariable) classes.add("type-variable")
            }
        }
        if (node.isVar) {
            classes.add("keyword")
        }
        if (classes.isNotEmpty()) {
            annotatedSourceFile.annotate(node, Style(classes))
        }
        return true
    }

    override fun visit(node: BlockComment): Boolean {
        annotatedSourceFile.annotate(node,
                Style(if (node.isDocComment) setOf("comment", "javadoc") else setOf("comment")))
        disableKeywords(node)
        return true
    }

    override fun visit(node: Javadoc): Boolean {
        annotatedSourceFile.annotate(node, Style("comment", "javadoc"))
        disableKeywords(node)
        return true
    }

    override fun visit(node: LineComment): Boolean {
        annotatedSourceFile.annotate(node, Style("comment"))
        disableKeywords(node)
        return true
    }

    override fun visit(node: StringLiteral): Boolean {
        annotatedSourceFile.annotate(node, Style("string-literal"))
        disableKeywords(node)
        return true
    }

    private fun disableKeywords(node: ASTNode) {
        noKeywordRanges.add(Range.closedOpen(node.startPosition, node.startPosition + node.length))
    }

    override fun visit(node: NumberLiteral): Boolean {
        annotatedSourceFile.annotate(node, Style("number-literal"))
        return true
    }

    override fun visit(node: NormalAnnotation): Boolean {
        // we just want the name and the @
        annotatedSourceFile.annotate(node.typeName.startPosition - 1, node.typeName.length + 1,
                Style("annotation"))
        return true
    }

    override fun visit(node: MarkerAnnotation): Boolean {
        annotatedSourceFile.annotate(node, Style("annotation"))
        return true
    }

    override fun visit(node: TagElement): Boolean {
        if (node.tagName != null) {
            // we just want the name and the @
            annotatedSourceFile.annotate(node.startPosition, node.tagName.length + 1,
                    Style("javadoc-tag"))
        }
        return true
    }
}
