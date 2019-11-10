package at.yawk.javabrowser

import com.google.common.base.Ascii
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.eclipse.collections.impl.factory.primitive.CharCharMaps
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.compiler.ITerminalSymbols
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import java.io.EOFException
import java.io.Reader

/**
 * @author yawkat
 */
object Tokenizer {
    data class Token(
            val text: String,
            val start: Int,
            val length: Int,
            val symbol: Boolean
    )

    private fun isAsciiTextChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    private class ShiftTracker {
        // ordered list. top 32 bits are the index in the string produced by this reader, bottom 32 bits are how far
        // behind we are from the raw source at that position.
        private val shiftLog = LongLists.mutable.empty()
        var position = 0
        private var totalShift = 0

        fun recordShiftBy(delta: Int) {
            totalShift += delta
            val outputPosition = position - totalShift
            if (!shiftLog.isEmpty && (shiftLog.last shr 32).toInt() == outputPosition) {
                shiftLog.removeAtIndex(shiftLog.size() - 1)
            }
            shiftLog.add((outputPosition.toLong() shl 32) or (Integer.toUnsignedLong(totalShift)))
        }

        fun translatePosition(outputPosition: Int): Int {
            val i = shiftLog.binarySearch(outputPosition.toLong() shl 32)
            val shift = if (i >= 0) {
                shiftLog[i].toInt()
            } else {
                val insertionPoint = i.inv()
                if (insertionPoint < shiftLog.size() && shiftLog[insertionPoint] shr 32 <= outputPosition) {
                    shiftLog[insertionPoint].toInt()
                } else {
                    if (insertionPoint == 0) {
                        0
                    } else {
                        shiftLog[insertionPoint - 1].toInt()
                    }
                }
            }
            return outputPosition + shift
        }
    }

    /**
     * This reader has multiple jobs:
     *
     * - Unescape escape sequences in the input.
     * - Split camel case words by adding intermediate spaces.
     * - Keep track of the offsets of the two operations.
     */
    internal class UnescapingSplittingReader(private val rawSource: CharArray, private val stringLiteral: Boolean)
        : Reader() {
        companion object {
            private val SINGLE_CHAR_ESCAPES = CharCharMaps.immutable.of()
                    .newWithKeyValue('b', '\b')
                    .newWithKeyValue('n', '\n')
                    .newWithKeyValue('t', '\t')
                    .newWithKeyValue('f', '\u000c')
                    .newWithKeyValue('r', '\r')
                    .newWithKeyValue('"', '"')
                    .newWithKeyValue('\'', '\'')

            private fun isOctalDigit(c: Char) = c in '0'..'7'
            private fun fromHex(c: Char) = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> (c - 'a') + 0xa
                in 'A'..'F' -> (c - 'A') + 0xa
                else -> throw IllegalArgumentException("Invalid hex character")
            }
        }

        private val unescapeTracker = ShiftTracker()

        private fun takeAndUnescapeCharacter(): Int {
            fun hasMore(n: Int = 1) = (unescapeTracker.position + n) <= rawSource.size

            if (!hasMore()) return -1

            if (rawSource[unescapeTracker.position] == '\\') {
                val escapeStart = unescapeTracker.position++
                if (!hasMore()) throw EOFException()
                val first = rawSource[unescapeTracker.position++]
                val escapeValue = when {
                    first == '\\' -> '\\'
                    stringLiteral && SINGLE_CHAR_ESCAPES.containsKey(first) -> SINGLE_CHAR_ESCAPES[first]
                    isOctalDigit(first) -> {
                        var v = first - '0'
                        if (hasMore() && isOctalDigit(
                                        rawSource[unescapeTracker.position])) {
                            v = (v shl 3) + (rawSource[unescapeTracker.position++] - '0')
                            if (hasMore() && isOctalDigit(
                                            rawSource[unescapeTracker.position]) && v < 0x20) {
                                v = (v shl 3) + (rawSource[unescapeTracker.position++] - '0')
                            }
                        }
                        v.toChar()
                    }
                    // technically we can't do this in one step. unicode escapes could again be part of string escape
                    // sequences.
                    first == 'u' -> {
                        while (hasMore() && rawSource[unescapeTracker.position] == 'u') {
                            unescapeTracker.position++
                        }
                        if (!hasMore(4)) throw EOFException()

                        ((fromHex(rawSource[unescapeTracker.position++]) shl 12) or
                                (fromHex(rawSource[unescapeTracker.position++]) shl 8) or
                                (fromHex(rawSource[unescapeTracker.position++]) shl 4) or
                                fromHex(rawSource[unescapeTracker.position++])).toChar()
                    }
                    else -> {
                        if (stringLiteral) throw IllegalArgumentException("Illegal escape sequence")
                        else {
                            // comments can have all the escapes they want
                            unescapeTracker.position -= 2 // backtrack
                            null
                        }
                    }
                }
                if (escapeValue != null) {
                    val escapeEnd = unescapeTracker.position
                    if (escapeValue == '\u0000') {
                        // skip NUL byte
                        unescapeTracker.recordShiftBy(escapeEnd - escapeStart)
                        if (unescapeTracker.position >= rawSource.size) return -1
                    } else {
                        unescapeTracker.recordShiftBy(escapeEnd - escapeStart - 1)
                        return escapeValue.toInt()
                    }
                }
            }
            return rawSource[unescapeTracker.position++].toInt()
        }

        private val splitTracker = ShiftTracker()
        private val splitLookahead = StringBuilder(3)

        override fun read(): Int {
            while (splitLookahead.length < 3) {
                val c = takeAndUnescapeCharacter()
                if (c == -1) break
                splitLookahead.append(c.toChar())
            }
            if (splitLookahead.isEmpty()) return -1

            var splitAfter = false
            // if we're at a position where the previous char is uppercase, the current char is uppercase and the next
            // char is lowercase, split.
            // URLEncoder -> URL Encoder
            if (isAsciiTextChar(splitLookahead[0]) && splitLookahead.length >= 3 && Ascii.isUpperCase(splitLookahead[1])
                    && Ascii.isLowerCase(splitLookahead[2])) {
                splitAfter = true
            }
            // split around numbers in names.
            if (splitLookahead.length >= 2) {
                if (isAsciiTextChar(splitLookahead[0]) && isAsciiDigit(splitLookahead[1])) splitAfter = true
                if (isAsciiDigit(splitLookahead[0]) && isAsciiTextChar(splitLookahead[1])) splitAfter = true
            }

            val ret = splitLookahead[0]

            if (splitAfter) {
                splitTracker.recordShiftBy(-1)
                splitLookahead[0] = ' ' // insert space to force split
            } else {
                splitTracker.position++
                splitLookahead.deleteCharAt(0)
            }

            return ret.toInt()
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            for (i in off until off + len) {
                val v = read()
                if (v == -1) return if (i - off == 0) -1 else i - off
                cbuf[i] = v.toChar()
            }
            return len
        }

        override fun close() {
        }

        fun translateOutputToRawPosition(outputPosition: Int) =
                unescapeTracker.translatePosition(splitTracker.translatePosition(outputPosition))
    }

    // our reader doesn't block.
    @Suppress("BlockingMethodInNonBlockingContext")
    internal suspend fun SequenceScope<Token>.tokenizeEnglish(offset: Int,
                                                              rawSource: CharArray,
                                                              stringLiteral: Boolean = true) {
        val analyzer = EnglishAnalyzer()
        val unescapingReader = UnescapingSplittingReader(rawSource, stringLiteral)
        val tokenStream = analyzer.tokenStream(null, unescapingReader)
        tokenStream.reset()
        while (tokenStream.incrementToken()) {
            val offsetAttribute = tokenStream.getAttribute(OffsetAttribute::class.java)
            val start = unescapingReader.translateOutputToRawPosition(offsetAttribute.startOffset())
            val end = unescapingReader.translateOutputToRawPosition(offsetAttribute.endOffset() - 1) + 1
            yield(Token(
                    text = tokenStream.getAttribute(CharTermAttribute::class.java).toString(),
                    start = offset + start,
                    length = end - start,
                    symbol = false
            ))
        }
        tokenStream.end()
    }

    fun tokenize(source: String) = sequence<Token> {
        val scanner = ToolFactory.createScanner(true, false, false, CompilerOptions.VERSION_9)
        scanner.source = source.toCharArray()
        scanner.resetTo(0, source.length - 1)
        while (true) {
            val token = scanner.nextToken
            if (token == ITerminalSymbols.TokenNameEOF) break
            val start = scanner.currentTokenStartPosition
            val end = scanner.currentTokenEndPosition + 1
            when (token) {
                ITerminalSymbols.TokenNameCOMMENT_BLOCK,
                ITerminalSymbols.TokenNameCOMMENT_LINE,
                ITerminalSymbols.TokenNameCOMMENT_JAVADOC,
                ITerminalSymbols.TokenNameIdentifier -> {
                    tokenizeEnglish(start, scanner.rawTokenSource, stringLiteral = false)
                }
                ITerminalSymbols.TokenNameCharacterLiteral,
                ITerminalSymbols.TokenNameStringLiteral -> {
                    tokenizeEnglish(start, scanner.rawTokenSource)
                }
                else -> {
                    val text = String(scanner.currentTokenSource)
                    yield(Token(
                            // escapes unicode escape sequences.
                            text = text,
                            start = start,
                            length = end - start,
                            symbol = text.none { isAsciiTextChar(it) }
                    ))
                }
            }
        }
    }
}