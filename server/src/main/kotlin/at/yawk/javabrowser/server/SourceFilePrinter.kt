package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.SourceAnnotation
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator
import org.intellij.lang.annotations.Language

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

        fun html(@Language("HTML") s: String)
        fun text(s: String, start: Int, end: Int)
    }

    private class SplitSourceFile(val sourceFile: AnnotatedSourceFile) : Sequence() {
        /**
         * Offset in the text for each independent text fragment. Text fragments are uninterrupted by newlines or
         * annotations.
         */
        val fragments: IntList
        /**
         * Offset in [fragments] for the start of each line.
         */
        val lines: IntList

        init {
            val fragments = IntLists.mutable.empty()
            val lines = IntLists.mutable.empty()

            var textIndex = 0
            var entryIndex = 0

            fun consumeText(until: Int) {
                while (textIndex < until) {
                    val nextLine = sourceFile.text.indexOf('\n', textIndex)
                    fragments.add(textIndex)
                    textIndex = if (nextLine != -1 && nextLine < until) {
                        lines.add(fragments.size()) // line starts after this fragment
                        // includes the \n
                        nextLine + 1
                    } else {
                        until
                    }
                }
            }

            fun consumeUntil(until: Int) {
                while (textIndex < until) {
                    if (sourceFile.entries.size > entryIndex && sourceFile.entries[entryIndex].start < until) {
                        val nextEntry = sourceFile.entries[entryIndex++]
                        consumeText(nextEntry.start) // advances textIndex

                        if (nextEntry.start + nextEntry.length > until) throw AssertionError(sourceFile.entries)

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
            this.fragments = fragments
        }

        fun lineStartTextIndex(line: Int): Int {
            val fi = lines[line]
            if (fi == fragments.size()) {
                // last empty line
                return sourceFile.text.length
            } else {
                return fragments[fi]
            }
        }

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

    private fun Emitter<*>.diffLineMarker(newLine: Int?, oldLine: Int?) {
        if (oldLine != null) {
            val id = "--- ${oldLine + 1}"
            html("<a href='#$id' id='$id' class='line line-diff' data-line='${oldLine + 1}'></a>")
        } else {
            html("<a class='line line-diff'></a>")
        }
        if (newLine != null) {
            val id = (newLine + 1).toString()
            html("<a href='#$id' id='$id' class='line line-diff' data-line='${newLine + 1}'></a>")
        } else {
            html("<a class='line line-diff'></a>")
        }
        html("<span class='diff-marker'>")
        when {
            newLine == null -> html("-")
            oldLine == null -> html("+")
            else -> html(" ")
        }
        html("</span>")
    }

    private fun Emitter<*>.normalLineMarker(line: Int) {
        val id = (line + 1).toString()
        html("<a href='#$id' id='$id' class='line' data-line='${line + 1}'></a>")
    }

    private class Cursor<M : Any>(val sourceFile: SplitSourceFile) {
        // offsets in sourceFile.entries
        private val openEntries = IntLists.mutable.empty()
        private val openEntriesMemory = ArrayList<M?>()
        var line = 0
        private var fragment = 0
        private var entryIndex = 0

        private val entry: AnnotatedSourceFile.Entry
            get() = sourceFile.sourceFile.entries[entryIndex]

        private val fragmentTextStart: Int
            get() = sourceFile.fragments[fragment]

        fun advanceLine(scope: Scope, emitter: Emitter<M>?) {
            val untilFragment =
                    if (line == sourceFile.lines.size() - 1) sourceFile.fragments.size()
                    else sourceFile.lines[line + 1]
            while (fragment < untilFragment) {
                while (!openEntries.isEmpty
                        && sourceFile.sourceFile.entries[openEntries.last].end == fragmentTextStart) {
                    emitter?.endAnnotation(scope, sourceFile.sourceFile.entries[openEntries.last].annotation,
                            openEntriesMemory.last()!!)
                    // pop
                    openEntries.removeAtIndex(openEntries.size() - 1)
                    openEntriesMemory.removeAt(openEntriesMemory.size - 1)
                }

                while (entryIndex < sourceFile.sourceFile.entries.size
                        && entry.start == fragmentTextStart) {
                    val memory: M? = emitter?.computeMemory(scope, entry.annotation)
                    emitter?.startAnnotation(scope, entry.annotation, memory!!)
                    if (entry.length == 0) {
                        emitter?.endAnnotation(scope, entry.annotation, memory!!)
                    } else {
                        // push
                        openEntries.add(entryIndex)
                        openEntriesMemory.add(memory)
                    }
                    entryIndex++
                }

                val start = fragmentTextStart
                fragment++
                if (emitter != null) {
                    val end =
                            if (fragment == sourceFile.fragments.size()) sourceFile.sourceFile.text.length
                            else fragmentTextStart
                    emitter.text(sourceFile.sourceFile.text, start, end)
                }
            }
            line++
        }

        fun emitStack(scope: Scope, emitter: Emitter<M>) {
            for (i in 0 until openEntries.size()) {
                val entry = sourceFile.sourceFile.entries[openEntries[i]]
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
            for (i in openEntries.size() - 1 downTo 0) {
                val entry = sourceFile.sourceFile.entries[openEntries[i]]
                if (entry.start + entry.length <= fragmentTextStart) {
                    throw AssertionError()
                }
                emitter.endAnnotation(scope, entry.annotation, openEntriesMemory[i]!!)
            }
        }
    }

    fun <M : Any> toHtmlSingle(emitter: Emitter<M>, sourceFile: AnnotatedSourceFile) {
        val split = SplitSourceFile(sourceFile)
        val cursor = Cursor<M>(split)
        while (cursor.line < split.lines.size()) {
            emitter.normalLineMarker(cursor.line)
            cursor.advanceLine(SourceFilePrinter.Scope.NORMAL, emitter)
        }
    }

    class Diff(sourceFile: AnnotatedSourceFile, diffWithOld: AnnotatedSourceFile) {
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
                    emitter.html("<span class='deletion'>")
                    cursorOld.emitStack(Scope.OLD, emitter)
                    while (cursorOld.line < edit.endA) {
                        emitter.diffLineMarker(null, cursorOld.line)
                        cursorOld.advanceLine(Scope.OLD, emitter)
                    }
                    cursorOld.rewindStack(Scope.OLD, emitter)
                    emitter.html("</span>")
                }
                if (type == Edit.Type.INSERT || type == Edit.Type.REPLACE) {
                    // render insertion
                    emitter.html("<span class='insertion'>")
                    cursorNew.emitStack(Scope.NEW, emitter)
                    while (cursorNew.line < edit.endB) {
                        emitter.diffLineMarker(cursorNew.line, null)
                        cursorNew.advanceLine(Scope.NEW, emitter)
                    }
                    cursorNew.rewindStack(Scope.NEW, emitter)
                    emitter.html("</span>")
                }
            }
            renderNoChangeRegion(new.lines.size())
        }
    }
}