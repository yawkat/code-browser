package at.yawk.javabrowser.server

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import java.util.BitSet
import java.util.Comparator
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.coroutines.experimental.buildSequence

/**
 * @author yawkat
 */
private const val MAX_DEPTH = 5
private val NO_MATCH_SENTINEL = IntArray(0)

class SearchIndex<K, V> {
    companion object {
        private val SPLIT_PATTERN = Pattern.compile("(?:\\.|[a-z0-9][A-Z]|[a-zA-Z][0-9])")

        internal fun split(s: String): List<String> {
            val out = ArrayList<String>()
            val matcher = SPLIT_PATTERN.matcher(s)
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

        internal fun commonPrefixLength(a: String, b: String, aOff: Int = 0, bOff: Int = 0): Int {
            var i = 0
            while (i + aOff < a.length && i + bOff < b.length && a[i + aOff] == b[i + bOff]) {
                i++
            }
            return i
        }

        private val ENTRY_COMPARATOR = Comparator.comparingInt<Entry<*>> { e ->
            e.input.weight.inv()
        }.thenComparingInt { e ->
            // this is a heuristic for finding the first "class name" character. Package names are assumed to be lowercase.
            // we can't simply use the last dot because there might be nested classes
            e.input.string.length - e.input.string.indexOfFirst { Ascii.isUpperCase(it) }
        }.thenComparingInt { e ->
            e.input.string.length
        }.thenComparing { e ->
            e.input.string
        }
    }

    private val categories = ConcurrentHashMap<K, List<Entry<V>>>()

    fun replace(categoryKey: K, strings: Iterator<Input<V>>) {
        val entries = ArrayList<Entry<V>>()
        strings.forEach { entries.add(Entry(it, split(it.string).toTypedArray())) }
        entries.sortWith(ENTRY_COMPARATOR)
        categories[categoryKey] = entries
    }

    fun find(query: String, includedCats: Set<K> = this.categories.keys) = buildSequence {
        val queryLower = query.toLowerCase(Locale.US)
        val candidates = ArrayList<List<Entry<V>>>(includedCats.size)
        val includedCategoriesFinal = ArrayList<K>(includedCats.size)
        // one loop to maintain order
        for (category in includedCats) {
            candidates.add(categories[category] ?: continue)
            includedCategoriesFinal.add(category)
        }

        val visited = candidates.map { BitSet(it.size) }
        for (depth in 0 until MAX_DEPTH) {
            // all the lists in candidates are sorted by entry length, so we can do a simple merge to keep order
            val indices = IntArray(candidates.size)
            while (true) {
                // find the next shortest unvisited entry in candidates
                var bestList = -1
                var bestEntry: Entry<V>? = null
                for ((j, list) in candidates.withIndex()) {
                    while (true) {
                        if (indices[j] >= list.size) {
                            break
                        }
                        if (visited[j][indices[j]]) {
                            indices[j]++
                            continue
                        }
                        val entryHere = list[indices[j]]
                        if (bestList == -1 || ENTRY_COMPARATOR.compare(bestEntry, entryHere) > 0) {
                            bestEntry = entryHere
                            bestList = j
                        }
                        break
                    }
                }
                if (bestList == -1) {
                    break
                }
                val result = Searcher(bestEntry!!.componentsLower, queryLower).search(depth)
                if (result != null) {
                    yield(SearchResult(query, includedCategoriesFinal[bestList], bestEntry, result))
                    visited[bestList][indices[bestList]] = true
                }
                indices[bestList]++
            }
        }
    }

    @VisibleForTesting
    internal class Searcher(private val groups: Array<String>, private val query: String) {
        private val memo = arrayOfNulls<IntArray>(groups.size * query.length)

        fun search(maxDepth: Int) = memo(0, 0, maxDepth)

        private fun impl(groupsI: Int, queryI: Int, maxDepth: Int): IntArray? {
            val maxSubstringEnd = queryI + commonPrefixLength(groups[groupsI], query, 0, queryI)
            var i = queryI
            while (i <= maxSubstringEnd) {
                val next = memo(groupsI + 1, i, if (i == queryI) maxDepth else maxDepth - 1)
                if (next != null) {
                    next[groupsI] = i - queryI
                    return next
                }
                i++
            }
            return null
        }

        private fun memo(groupsI: Int, queryI: Int, maxDepth: Int): IntArray? {
            // base case: empty query -> everything matches
            if (queryI >= query.length) {
                return IntArray(groups.size)
            }
            if (maxDepth <= 0) {
                return null
            }
            // base case: empty haystack and non-empty query -> nothing matches
            if (groupsI >= groups.size) {
                return null
            }

            val i = groupsI + queryI * groups.size
            val present = memo[i]
            if (present == null) {
                val computed = impl(groupsI, queryI, maxDepth)
                memo[i] = computed ?: NO_MATCH_SENTINEL
                return computed
            } else {
                if (present === NO_MATCH_SENTINEL) {
                    return null
                } else {
                    if (present.count { it != 0 } > maxDepth) {
                        return null
                    }
                    return present
                }
            }
        }
    }

    data class SearchResult<K, V> internal constructor(
            val query: String,
            val key: K,
            val entry: Entry<V>,
            val match: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            return other is SearchResult<*, *> &&
                    other.query == query &&
                    other.key == key &&
                    other.key == key &&
                    other.entry.input.string == entry.input.string &&
                    other.entry.input.value == entry.input.value &&
                    other.match.contentEquals(match)
        }

        override fun hashCode(): Int {
            return Objects.hash(query, key, entry.input.string, match.contentHashCode())
        }
    }

    data class Input<V>(
            val string: String,
            val value: V,
            val weight: Int
    )

    internal class Entry<V>(val input: Input<V>, val componentsLower: Array<String>)
}