package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.ASTNode
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * @author yawkat
 */

class AnnotatedSourceFile(private val text: String) {
    companion object {
        const val URI = ""
    }

    private val entries = ArrayList<Entry>()
    fun annotate(node: ASTNode, annotation: SourceAnnotation) = annotate(node.startPosition, node.length, annotation)

    fun annotate(start: Int, length: Int, annotation: SourceAnnotation) {
        entries.add(Entry(start, length, annotation))
    }

    private fun sort() {
        entries.sortBy { it.start }
    }

    fun toHtml(toNode: (SourceAnnotation, List<Node>) -> List<Node>): List<Node> {
        sort()
        var entryIndex = 0
        var textIndex = 0

        fun consumeUntil(out: MutableList<Node>, until: Int) {
            while (textIndex < until) {
                if (entries.size > entryIndex && entries[entryIndex].start < until) {
                    val nextEntry = entries[entryIndex++]
                    if (nextEntry.start != textIndex) {
                        out.add(TextNode(text.substring(textIndex, nextEntry.start), URI))
                    }
                    textIndex = nextEntry.start

                    if (nextEntry.start + nextEntry.length > until) throw AssertionError(entries)

                    val nest = ArrayList<Node>()
                    consumeUntil(nest, nextEntry.start + nextEntry.length)
                    out.addAll(toNode(nextEntry.annotation, nest))
                } else {
                    out.add(TextNode(text.substring(textIndex, until), URI))
                    textIndex = until
                }
            }
        }

        val out = ArrayList<Node>()
        consumeUntil(out, text.length)
        return out
    }

    private data class Entry(
            val start: Int,
            val length: Int,
            val annotation: SourceAnnotation
    )
}
