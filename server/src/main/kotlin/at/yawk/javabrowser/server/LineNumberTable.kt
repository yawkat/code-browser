package at.yawk.javabrowser.server

import org.eclipse.collections.impl.factory.primitive.IntLists

/**
 * @author yawkat
 */
class LineNumberTable(text: CharSequence) {
    private val lineOffsetTable = IntLists.mutable.of(0)

    init {
        for (i in text.indices) {
            if (text[i] == '\n') {
                lineOffsetTable.add(i + 1)
            }
        }
    }

    fun lineAt(index: Int): Int {
        val res = lineOffsetTable.binarySearch(index)
        return if (res < 0) res.inv() else res + 1
    }
}