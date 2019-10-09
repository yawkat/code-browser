package at.yawk.javabrowser.server

import org.eclipse.collections.api.map.primitive.CharObjectMap
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import java.io.Writer

/**
 * @author yawkat
 */
class Escaper private constructor(private val escapes: CharObjectMap<String>) {
    companion object {
        val HTML = Escaper(
                '"' to "&quot;",
                '\'' to "&#39;",
                '&' to "&amp;",
                '<' to "&lt;",
                '>' to "&gt;"
        )
    }

    private constructor(vararg escapes: Pair<Char, String>) :
            this(CharObjectMaps.mutable.from(escapes.asList(), { it.first }, { it.second }))

    private inline fun escape0(
            input: String, start: Int, end: Int,
            push: (String) -> Unit,
            copyRange: (Int, Int) -> Unit,
            copyRangeEnd: (Int) -> Unit
    ) {
        var fragmentStart = start
        for (i in start until end) {
            val c = input[i]
            val escape = escapes[c]
            if (escape != null) {
                if (i != fragmentStart) {
                    copyRange(fragmentStart, i)
                }
                push(escape)
                fragmentStart = i + 1
            }
        }
        if (fragmentStart != end) {
            copyRangeEnd(fragmentStart)
        }
    }

    private inline fun escape0(
            input: String, start: Int, end: Int,
            push: (String) -> Unit,
            copyRange: (Int, Int) -> Unit
    ) = escape0(input, start, end, push, copyRange, { copyRange(it, end) })

    fun escape(s: String, start: Int = 0, end: Int = s.length): String {
        var builder: StringBuilder? = null
        escape0(
                s, start, end,
                push = {
                    if (builder == null) builder = StringBuilder(s.length + it.length + 16)

                    builder!!.append(it)
                },
                copyRange = { rangeStart, rangeEnd ->
                    if (builder == null) builder = StringBuilder(s.length)
                    builder!!.append(s, rangeStart, rangeEnd)
                },
                copyRangeEnd = { rangeStart ->
                    assert((builder == null) == (start == rangeStart))
                    if (builder != null) {
                        builder!!.append(s, rangeStart, end)
                    } // else no escape
                }
        )
        return builder?.toString() ?: s.substring(start, end)
    }

    fun escape(writer: Writer, s: String, start: Int = 0, end: Int = s.length) {
        escape0(
                s, start, end,
                push = { writer.write(it) },
                copyRange = { rangeStart, rangeEnd -> writer.write(s, rangeStart, rangeEnd - rangeStart) }
        )
    }
}