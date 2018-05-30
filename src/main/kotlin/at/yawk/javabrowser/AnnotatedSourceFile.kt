package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.ASTNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/**
 * @author yawkat
 */

class AnnotatedSourceFile(val text: String) {
    companion object {
        const val URI = ""
    }

    private val entries = ArrayList<Entry>()
    fun annotate(node: ASTNode, annotation: SourceAnnotation) = annotate(node.startPosition, node.length, annotation)

    fun annotate(start: Int, length: Int, annotation: SourceAnnotation) {
        entries.add(Entry(start, length, annotation))
    }

    private fun sort() {
        entries.sortWith(Comparator.comparingInt { it: Entry -> it.start }.thenComparingInt { it -> it.length.inv() })
    }

    fun toHtml(toNode: (SourceAnnotation, List<Node>) -> List<Node>): List<Node> {
        sort()
        var line = 1
        var entryIndex = 0
        var textIndex = 0

        fun lineMarker(out: MutableList<Node>) {
            val link = Element(Tag.valueOf("a"), AnnotatedSourceFile.URI)
            val lineStr = line.toString()
            link.attr("href", "#$lineStr")
            link.attr("id", lineStr)
            link.attr("class", "line")
            link.attr("data-line", lineStr)
            out.add(link)

            line++
        }

        fun consumeText(out: MutableList<Node>, until: Int) {
            while (textIndex < until) {
                val nextLine = text.indexOf('\n', textIndex)
                if (nextLine != -1 && nextLine < until) {
                    out.add(TextNode(text.substring(textIndex, nextLine + 1), URI))
                    textIndex = nextLine + 1
                    lineMarker(out)
                } else {
                    out.add(TextNode(text.substring(textIndex, until), URI))
                    textIndex = until
                }
            }
        }

        fun consumeUntil(out: MutableList<Node>, until: Int) {
            while (textIndex < until) {
                if (entries.size > entryIndex && entries[entryIndex].start < until) {
                    val nextEntry = entries[entryIndex++]
                    consumeText(out, nextEntry.start)
                    textIndex = nextEntry.start

                    if (nextEntry.start + nextEntry.length > until) throw AssertionError(entries)

                    val nest = ArrayList<Node>()
                    consumeUntil(nest, nextEntry.start + nextEntry.length)
                    out.addAll(toNode(nextEntry.annotation, nest))
                } else {
                    consumeText(out, until)
                    textIndex = until
                }
            }
        }

        val out = ArrayList<Node>()
        lineMarker(out)
        consumeUntil(out, text.length)
        return out
    }

    private data class Entry(
            val start: Int,
            val length: Int,
            val annotation: SourceAnnotation
    )
}
