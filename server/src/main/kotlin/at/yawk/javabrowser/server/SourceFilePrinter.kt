package at.yawk.javabrowser.server

import at.yawk.javabrowser.IntRangeSet
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.SourceAnnotation
import com.google.common.collect.Iterators
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator
import java.util.Collections
import kotlin.math.min

/**
 * @author yawkat
 */
object SourceFilePrinter {
    enum class Scope {
        NORMAL, NEW, OLD
    }

    interface Emitter<M> {
        fun computeMemory(scope: Scope, annotation: SourceAnnotation): M
        fun startAnnotation(scope: Scope, annotation: SourceAnnotation, memory: M)
        fun endAnnotation(scope: Scope, annotation: SourceAnnotation, memory: M)

        fun text(s: String, start: Int, end: Int)

        fun beginInsertion()
        fun beginDeletion()
        fun beginHighlight()
        fun endInsertion()
        fun endDeletion()
        fun endHighlight()

        fun diffLineMarker(newLine: Int?, oldLine: Int?)
        fun normalLineMarker(line: Int)
    }

    private class SplitSourceFile(val sourceFile: ServerSourceFile) : Sequence() {
        /**
         * Offset for the start of each line.
         */
        val lines: IntList

        init {
            val lines = IntLists.mutable.empty()

            var textIndex = 0

            lines.add(0) // first line starts at 0
            while (textIndex < sourceFile.text.length) {
                val nextLine = sourceFile.text.indexOf('\n', textIndex)
                textIndex = if (nextLine != -1 && nextLine < sourceFile.text.length) {
                    lines.add(nextLine + 1) // line starts after this fragment
                    // includes the \n
                    nextLine + 1
                } else {
                    sourceFile.text.length
                }
            }

            this.lines = lines
        }

        fun lineStartTextIndex(line: Int) = lines[line]

        fun lineEndTextIndex(line: Int): Int {
            if (line == lines.size() - 1) {
                // last line
                return sourceFile.text.length
            } else {
                return lineStartTextIndex(line + 1)
            }
        }

        override fun size() = lines.size()
    }

    private object SplitSourceFileComparator : SequenceComparator<SplitSourceFile>() {
        override fun hash(seq: SplitSourceFile, ptr: Int): Int {
            return seq.sourceFile.text.substring(seq.lineStartTextIndex(ptr), seq.lineEndTextIndex(ptr)).hashCode()
        }

        override fun equals(a: SplitSourceFile, ai: Int, b: SplitSourceFile, bi: Int): Boolean {
            return a.sourceFile.text.substring(a.lineStartTextIndex(ai), a.lineEndTextIndex(ai)) ==
                    b.sourceFile.text.substring(b.lineStartTextIndex(bi), b.lineEndTextIndex(bi))
        }

    }

    private class Cursor<M : Any>(
            val sourceFile: ServerSourceFile,
            val textFilter: IntRangeSet? = null,
            highlight: IntRangeSet? = null
    ) {
        private val annotations = Iterators.peekingIterator(sourceFile.annotations.iterator())
        private val highlightIterator = Iterators.peekingIterator(highlight?.iterator() ?: Collections.emptyIterator())

        private val openEntriesAnnotations = ArrayList<PositionedAnnotation>()
        private val openEntriesMemory = ArrayList<M?>()

        var line = 0

        private val annotation: PositionedAnnotation
            get() = annotations.peek()

        var fragmentTextStart = 0
            private set

        val endOfFile: Boolean
            get() = fragmentTextStart >= sourceFile.text.length

        private var highlightEmitted = false
        private val inHighlight: Boolean
            get() = highlightIterator.hasNext() && fragmentTextStart in highlightIterator.peek()

        private fun popDoneAnnotations(scope: Scope, emitter: Emitter<M>?) {
            while (openEntriesAnnotations.isNotEmpty() && openEntriesAnnotations.last().end <= fragmentTextStart) {
                if (highlightEmitted) {
                    emitter?.endHighlight()
                    highlightEmitted = false
                }

                emitter?.endAnnotation(scope, openEntriesAnnotations.last().annotation,
                        openEntriesMemory.last()!!)
                // pop
                openEntriesAnnotations.removeAt(openEntriesAnnotations.size - 1)
                openEntriesMemory.removeAt(openEntriesMemory.size - 1)
            }
        }

        private fun pushNewAnnotations(scope: Scope, emitter: Emitter<M>?) {
            while (annotations.hasNext() && annotation.start <= fragmentTextStart) {
                if (textFilter == null || textFilter.intersects(annotation.start, annotation.end)) {
                    val memory: M? = emitter?.computeMemory(scope, annotation.annotation)
                    emitter?.startAnnotation(scope, annotation.annotation, memory!!)
                    if (annotation.end <= fragmentTextStart) {
                        emitter?.endAnnotation(scope, annotation.annotation, memory!!)
                    } else {
                        // push
                        openEntriesAnnotations.add(annotation)
                        openEntriesMemory.add(memory)
                    }
                }
                annotations.next()
            }
        }

        fun advanceLine(scope: Scope, emitter: Emitter<M>?) {
            var untilText = sourceFile.text.indexOf('\n', fragmentTextStart)
            if (untilText == -1) {
                untilText = sourceFile.text.length
            } else {
                untilText++ // include \n
            }
            if (emitter == null) {
                // shortcut
                fragmentTextStart = untilText
                popDoneAnnotations(scope, emitter)
                pushNewAnnotations(scope, emitter)
            } else {
                while (fragmentTextStart < untilText) {
                    if (highlightIterator.hasNext() && highlightIterator.peek().last < fragmentTextStart) {
                        highlightIterator.next()
                    }

                    if (highlightEmitted && !inHighlight) {
                        emitter.endHighlight()
                        highlightEmitted = false
                    }

                    popDoneAnnotations(scope, emitter)
                    pushNewAnnotations(scope, emitter)

                    if (!highlightEmitted && inHighlight) {
                        emitter.beginHighlight()
                        highlightEmitted = true
                    }

                    val start = fragmentTextStart

                    // at best, proceed to end
                    var nextFragmentStart = untilText
                    // ... but no further than the next annotation start
                    if (annotations.hasNext()) {
                        nextFragmentStart = min(nextFragmentStart, annotation.start)
                    }
                    // ... or further than the next annotation end
                    if (openEntriesAnnotations.isNotEmpty()) {
                        nextFragmentStart = min(nextFragmentStart, openEntriesAnnotations.last().end)
                    }
                    // ... or further than the next highlight start
                    if (highlightIterator.hasNext() && highlightIterator.peek().first > start) {
                        nextFragmentStart = min(nextFragmentStart,highlightIterator.peek().first)
                    }
                    // ... or further than the current highlight end
                    if (highlightIterator.hasNext()) {
                        nextFragmentStart = min(nextFragmentStart, highlightIterator.peek().last + 1)
                    }
                    fragmentTextStart = nextFragmentStart

                    emitter.text(sourceFile.text, start, fragmentTextStart)
                }
                // in case an annotation ends exactly at the newline
                popDoneAnnotations(scope, emitter)
            }
            line++
        }

        fun emitStack(scope: Scope, emitter: Emitter<M>) {
            for (i in openEntriesAnnotations.indices) {
                val entry = openEntriesAnnotations[i]
                if (entry.end <= fragmentTextStart) {
                    throw AssertionError()
                }
                if (openEntriesMemory[i] == null) {
                    openEntriesMemory[i] = emitter.computeMemory(scope, entry.annotation)
                }
                emitter.startAnnotation(scope, entry.annotation, openEntriesMemory[i]!!)
            }
        }

        fun rewindStack(scope: Scope, emitter: Emitter<M>) {
            for (i in openEntriesAnnotations.size - 1 downTo 0) {
                val entry = openEntriesAnnotations[i]
                if (entry.end <= fragmentTextStart) {
                    throw AssertionError()
                }
                emitter.endAnnotation(scope, entry.annotation, openEntriesMemory[i]!!)
            }
        }
    }

    fun <M : Any> toHtmlSingle(emitter: Emitter<M>, sourceFile: ServerSourceFile) {
        val cursor = Cursor<M>(sourceFile)
        while (!cursor.endOfFile) {
            emitter.normalLineMarker(cursor.line)
            cursor.advanceLine(SourceFilePrinter.Scope.NORMAL, emitter)
        }
    }

    class Diff(sourceFile: ServerSourceFile, diffWithOld: ServerSourceFile) {
        private val new = SplitSourceFile(sourceFile)
        private val old = SplitSourceFile(diffWithOld)
        private val diff: EditList

        init {
            val algorithm = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            diff = algorithm.diff(SplitSourceFileComparator, old, new)
        }

        val insertions: Int
            get() = diff.sumBy { it.endB - it.beginB }
        val deletions: Int
            get() = diff.sumBy { it.endA - it.beginA }

        fun <M : Any> toHtml(emitter: Emitter<M>) {
            // avoid unnecessary work for the cursor that only provides the deletions
            val oldTextFilter = IntRangeSet()
            for (edit in diff) {
                if (edit.type == Edit.Type.DELETE || edit.type == Edit.Type.REPLACE) {
                    oldTextFilter.add(
                            old.lineStartTextIndex(edit.beginA),
                            old.lineEndTextIndex(edit.endA - 1)
                    )
                }
            }

            val cursorNew = Cursor<M>(new.sourceFile)
            val cursorOld = Cursor<M>(old.sourceFile, oldTextFilter)

            fun renderNoChangeRegion(endLineNew: Int) {
                if (cursorNew.line < endLineNew) {
                    cursorNew.emitStack(Scope.NEW, emitter)
                    while (cursorNew.line < endLineNew) {
                        emitter.diffLineMarker(cursorNew.line, cursorOld.line)
                        cursorNew.advanceLine(Scope.NEW, emitter)
                        cursorOld.advanceLine(Scope.OLD, null)
                    }
                    cursorNew.rewindStack(Scope.NEW, emitter)
                }
            }

            for (edit in diff) {
                val type = edit.type
                if (type == Edit.Type.EMPTY) continue

                renderNoChangeRegion(edit.beginB)
                if (edit.beginB != cursorNew.line || edit.beginA != cursorOld.line) {
                    throw AssertionError()
                }
                if (type == Edit.Type.DELETE || type == Edit.Type.REPLACE) {
                    // render deletion
                    emitter.beginDeletion()
                    cursorOld.emitStack(Scope.OLD, emitter)
                    while (cursorOld.line < edit.endA) {
                        emitter.diffLineMarker(null, cursorOld.line)
                        cursorOld.advanceLine(Scope.OLD, emitter)
                    }
                    cursorOld.rewindStack(Scope.OLD, emitter)
                    emitter.endDeletion()
                }
                if (type == Edit.Type.INSERT || type == Edit.Type.REPLACE) {
                    // render insertion
                    emitter.beginInsertion()
                    cursorNew.emitStack(Scope.NEW, emitter)
                    while (cursorNew.line < edit.endB) {
                        emitter.diffLineMarker(cursorNew.line, null)
                        cursorNew.advanceLine(Scope.NEW, emitter)
                    }
                    cursorNew.rewindStack(Scope.NEW, emitter)
                    emitter.endInsertion()
                }
            }
            renderNoChangeRegion(new.lines.size())
        }
    }

    class Partial(private val sourceFile: ServerSourceFile) {
        private val interest = IntRangeSet()

        fun addInterest(start: Int, end: Int) {
            interest.add(start, end)
        }

        fun <M : Any> createRenderer(contextBefore: Int, contextAfter: Int): Renderer<M> {
            var lineStart = 0
            val backlog = IntLists.mutable.empty()
            var contextLead = 0
            val lineSet = IntRangeSet()

            while (lineStart < sourceFile.text.length) {
                var lineEnd = sourceFile.text.indexOf('\n', lineStart)
                if (lineEnd == -1) lineEnd = sourceFile.text.length
                else lineEnd++ // include \n

                when {
                    // check if line contains a highlight
                    interest.intersects(lineStart, lineEnd) -> {
                        lineSet.add(if (backlog.isEmpty) lineStart else backlog[0], lineEnd)
                        backlog.clear()
                        contextLead = contextAfter
                    }
                    // is this line just after a highlight?
                    contextLead > 0 -> {
                        lineSet.add(lineStart, lineEnd)
                        contextLead--
                    }
                    // else, add it to the backlog - a highlight below might still include it.
                    else -> {
                        if (backlog.size() >= contextBefore) backlog.removeAtIndex(0)
                        backlog.add(lineStart)
                    }
                }

                lineStart = lineEnd
            }
            return Renderer(sourceFile, interest, lineSet)
        }

        class Renderer<M : Any>(
                sourceFile: ServerSourceFile,
                highlight: IntRangeSet,
                includeLines: IntRangeSet
        ) {
            private val itr = includeLines.iterator()
            private val cursor = Cursor<M>(sourceFile, includeLines, highlight)

            fun renderNextRegion(emitter: Emitter<M>) {
                val region = itr.next()
                if (cursor.fragmentTextStart >= region.first && region.first != 0) {
                    throw AssertionError("already at region start?")
                }
                while (cursor.fragmentTextStart < region.first) {
                    cursor.advanceLine(SourceFilePrinter.Scope.NORMAL, null)
                }
                if (cursor.fragmentTextStart != region.first) throw AssertionError("not line-aligned")
                cursor.emitStack(SourceFilePrinter.Scope.NORMAL, emitter)
                while (cursor.fragmentTextStart <= region.last) {
                    emitter.normalLineMarker(cursor.line)
                    cursor.advanceLine(SourceFilePrinter.Scope.NORMAL, emitter)
                }
                cursor.rewindStack(SourceFilePrinter.Scope.NORMAL, emitter)
                if (cursor.fragmentTextStart != region.last + 1) throw AssertionError("not line-aligned")
            }

            fun hasMore(): Boolean {
                return itr.hasNext()
            }
        }
    }
}