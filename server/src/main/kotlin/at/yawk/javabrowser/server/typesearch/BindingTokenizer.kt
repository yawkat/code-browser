package at.yawk.javabrowser.server.typesearch

import java.util.Locale
import java.util.regex.Pattern

private fun splitByPattern(s: String, splitPattern: Pattern): List<String> {
    val out = ArrayList<String>()
    val matcher = splitPattern.matcher(s)
    var last = 0
    while (matcher.find()) {
        val end = matcher.start() + 1
        out.add(s.substring(last, end).toLowerCase(Locale.US))
        last = end
    }
    if (last != s.length) {
        out.add(s.substring(last).toLowerCase(Locale.US))
    }
    return out
}

interface BindingTokenizer {
    fun tokenize(s: String): List<String>

    object Java : BindingTokenizer {
        private val JAVA_SPLIT_PATTERN = Pattern.compile("(?:\\.|[a-z0-9][A-Z]|[a-zA-Z][0-9])")

        override fun tokenize(s: String) = splitByPattern(s, JAVA_SPLIT_PATTERN)
    }

    object Bytecode : BindingTokenizer {
        private val BYTECODE_SPLIT_PATTERN = Pattern.compile("(?:/|^L|[a-z0-9][A-Z]|[a-zA-Z][0-9])")

        override fun tokenize(s: String) = splitByPattern(s, BYTECODE_SPLIT_PATTERN)
    }
}