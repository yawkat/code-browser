package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.LocalVariableRef
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.appendChildren
import io.dropwizard.views.View
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Tag

/**
 * @author yawkat
 */
@Suppress("unused")
class SourceFileView(
        val artifactId: String,
        val sourceFilePath: String,

        private val bindingResolver: BindingResolver,
        private val sourceFile: AnnotatedSourceFile
) : View("source-file.ftl") {

    val codeHtml by lazy {
        val doc = Document("")
        doc.outputSettings().prettyPrint(false)
        val pre = doc.appendElement("pre")
        pre.appendChildren(sourceFile.toHtml(::toNode))
        pre.html()!! // inner HTML, we need the pre so jsoup properly formats
    }

    private fun toNode(annotation: SourceAnnotation, members: List<Node>): List<Node> = when (annotation) {
        is BindingRef -> {
            val uris = bindingResolver.resolveBinding(artifactId, annotation.binding)
            if (uris.isEmpty()) {
                members
            } else {
                listOf(Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                    if (annotation.type == BindingRefType.SUPER_TYPE || annotation.type == BindingRefType.SUPER_METHOD) {
                        // no href for super references so that we don't hide our own declaration link
                        it.attr("data-super-href", uris[0].toASCIIString())
                    } else {
                        it.attr("href", uris[0].toASCIIString())
                    }
                    it.attr("id", "ref-${annotation.id}")
                    it.appendChildren(members)
                })
            }
        }
        is BindingDecl -> {
            val link = Element(Tag.valueOf("a"), AnnotatedSourceFile.URI)
            link.attr("id", annotation.binding)
            link.attr("href", BindingResolver.bindingHash(annotation.binding))
            link.appendChild(Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also { moreInfo ->
                moreInfo.attr("class", "show-refs")
                moreInfo.attr("href", "javascript:showReferences('${annotation.binding}')")
            })
            link.appendChildren(members)

            listOf(link)
        }
        is Style -> listOf(Element(Tag.valueOf("span"), AnnotatedSourceFile.URI).also {
            it.attr("class", annotation.styleClass.joinToString(" "))
            it.appendChildren(members)
        })
        is LocalVariableRef -> listOf(Element(Tag.valueOf("span"), AnnotatedSourceFile.URI).also {
            it.attr("class", "local-variable")
            it.attr("data-local-variable", annotation.id)
            it.appendChildren(members)
        })
    }
}
