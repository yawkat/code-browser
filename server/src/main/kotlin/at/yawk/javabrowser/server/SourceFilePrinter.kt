package at.yawk.javabrowser.server

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
        fun endInsertion()
        fun endDeletion()

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
            var entryIndex = 0

            fun consumeText(until: Int) {
                while (textIndex < until) {
                    val nextLine = sourceFile.text.indexOf('\n', textIndex)
                    textIndex = if (nextLine != -1 && nextLine < until) {
                        lines.add(nextLine + 1) // line starts after this fragment
                        // includes the \n
                        nextLine + 1
                    } else {
                        until
                    }
                }
            }

            fun consumeUntil(until: Int) {
                while (textIndex < until) {
                    if (sourceFile.annotationList.size > entryIndex &&
                            sourceFile.annotationList[entryIndex].start < until) {
                        val nextEntry = sourceFile.annotationList[entryIndex++]
                        consumeText(nextEntry.start) // advances textIndex

                        if (nextEntry.start + nextEntry.length > until) throw AssertionError(sourceFile.annotations)

                        consumeUntil(nextEntry.start + nextEntry.length)
                    } else {
                        consumeText(until)
                        textIndex = until
                    }
                }
            }

            lines.add(0) // first line starts at 0
            consumeUntil(sourceFile.text.length)

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

    private class Cursor<M : Any>(val sourceFile: SplitSourceFile) {
        private val iterator = Iterators.peekingIterator(sourceFile.sourceFile.annotations.iterator())

        private val openEntriesAnnotations = ArrayList<PositionedAnnotation>()
        private val openEntriesMemory = ArrayList<M?>()

        var line = 0

        private val entry: PositionedAnnotation
            get() = iterator.peek()

        private var fragmentTextStart = 0

        fun advanceLine(scope: Scope, emitter: Emitter<M>?) {
            val untilText = sourceFile.lineEndTextIndex(line)
            while (fragmentTextStart < untilText) {
                while (openEntriesAnnotations.isNotEmpty() && openEntriesAnnotations.last().end == fragmentTextStart) {
                    emitter?.endAnnotation(scope, openEntriesAnnotations.last().annotation,
                            openEntriesMemory.last()!!)
                    // pop
                    openEntriesAnnotations.removeAt(openEntriesAnnotations.size - 1)
                    openEntriesMemory.removeAt(openEntriesMemory.size - 1)
                }

                while (iterator.hasNext() && entry.start == fragmentTextStart) {
                    val memory: M? = emitter?.computeMemory(scope, entry.annotation)
                    emitter?.startAnnotation(scope, entry.annotation, memory!!)
                    if (entry.length == 0) {
                        emitter?.endAnnotation(scope, entry.annotation, memory!!)
                    } else {
                        // push
                        openEntriesAnnotations.add(entry)
                        openEntriesMemory.add(memory)
                    }
                    iterator.next()
                }

                val start = fragmentTextStart

                // at best, proceed to end
                var nextFragmentStart = untilText
                // ... but no further than the next annotation start
                if (iterator.hasNext() && entry.start < nextFragmentStart) {
                    nextFragmentStart = entry.start
                }
                // ... or further than the next annotation end.
                if (openEntriesAnnotations.isNotEmpty() && openEntriesAnnotations.last().end < nextFragmentStart) {
                    nextFragmentStart = openEntriesAnnotations.last().end
                }
                fragmentTextStart = nextFragmentStart

                emitter?.text(sourceFile.sourceFile.text, start, fragmentTextStart)
            }
            line++
        }

        fun emitStack(scope: Scope, emitter: Emitter<M>) {
            for (i in openEntriesAnnotations.indices) {
                val entry = openEntriesAnnotations[i]
                if (entry.start + entry.length <= fragmentTextStart) {
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
                if (entry.start + entry.length <= fragmentTextStart) {
                    throw AssertionError()
                }
                emitter.endAnnotation(scope, entry.annotation, openEntriesMemory[i]!!)
            }
        }
    }

    fun <M : Any> toHtmlSingle(emitter: Emitter<M>, sourceFile: ServerSourceFile) {
        val split = SplitSourceFile(sourceFile)
        val cursor = Cursor<M>(split)
        while (cursor.line < split.lines.size()) {
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
            val cursorNew = Cursor<M>(new)
            val cursorOld = Cursor<M>(old)

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
}