package at.yawk.javabrowser

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/**
 * @author yawkat
 */

data class AnnotatedSourceFile(
        val text: String,
        @Suppress("MemberVisibilityCanBePrivate") val entries: MutableList<Entry> = ArrayList()) {
    companion object {
        const val URI = ""
    }

    fun annotate(start: Int, length: Int, annotation: SourceAnnotation) {
        entries.add(Entry(start, length, annotation))
    }

    fun bake() {
        entries.sortWith(Comparator
                .comparingInt { it: Entry -> it.start }
                // first 0-length items, then the longest items
                // this avoids unnecessary nesting
                .thenComparingInt { it -> if (it.length == 0) Int.MIN_VALUE else it.length.inv() })

        // try to merge entries that affect the same text
        var i = 0
        while (i < entries.size) {
            val head = entries[i]
            var j = i + 1
            var merged: SourceAnnotation? = null
            while (merged == null && j < entries.size &&
                    entries[j].start == head.start && entries[j].length == head.length) {
                merged = tryMerge(head.annotation, entries[j++].annotation)
            }
            if (merged == null) {
                i++
            } else {
                entries.removeAt(j - 1)
                entries[i] = head.copy(annotation = merged)
            }
        }
    }

    private fun tryMerge(a: SourceAnnotation, b: SourceAnnotation): SourceAnnotation? {
        if (a is Style && b is Style) {
            return Style(a.styleClass + b.styleClass)
        }
        if (a is BindingRef && b is BindingRef) {
            // one method can override multiple supers
            if (a.type != BindingRefType.SUPER_METHOD && b.type != BindingRefType.SUPER_METHOD) return null
            if (a.type != BindingRefType.SUPER_TYPE && b.type != BindingRefType.SUPER_TYPE) return null
            throw RuntimeException("Duplicate ref: $a / $b")
        }
        return null
    }

    fun toHtml(toNode: (SourceAnnotation, List<Node>) -> List<Node>): List<Node> {
        var line = 1
        var entryIndex = 0
        var textIndex = 0

        fun lineMarker(out: MutableList<Node>) {
            val link = Element(Tag.valueOf("a"), URI)
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
                    out.add(TextNode(text.substring(textIndex, nextLine + 1),
                            URI))
                    textIndex = nextLine + 1
                    lineMarker(out)
                } else {
                    out.add(TextNode(text.substring(textIndex, until),
                            URI))
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

    data class Entry(
            val start: Int,
            val length: Int,
            val annotation: SourceAnnotation
    )
}
