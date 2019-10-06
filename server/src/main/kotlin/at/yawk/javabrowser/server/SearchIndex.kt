package at.yawk.javabrowser.server

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import java.util.BitSet
import java.util.Comparator
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

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
    }

    private val categories = ConcurrentHashMap<K, List<Entry<V>>>()

    fun replace(categoryKey: K, strings: Iterator<Input<V>>) {
        categories[categoryKey] = strings.asSequence().map {
            Entry(it.value, SplitEntry(it.string), SplitEntry(it.string.substring(it.string.lastIndexOf('.') + 1)))
        }.sortedBy { it.name }.toList()
    }

    fun find(query: String, includedCats: Set<K> = this.categories.keys) = sequence {
        val queryLower = query.toLowerCase(Locale.US)
        val candidates = ArrayList<List<Entry<V>>>(includedCats.size)
        val includedCategoriesFinal = ArrayList<K>(includedCats.size)
        // one loop to maintain order
        for (category in includedCats) {
            candidates.add(categories[category] ?: continue)
            includedCategoriesFinal.add(category)
        }

        val visited = candidates.map { BitSet(it.size) }
        // depth 0 is the same as 1, except we only search the class name.
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
                        if (bestList == -1 || bestEntry!!.name > entryHere.name) {
                            bestEntry = entryHere
                            bestList = j
                        }
                        break
                    }
                }
                if (bestList == -1) {
                    break
                }
                val result =
                        if (depth == 0) Searcher(bestEntry!!.simpleName.componentsLower, queryLower).search(1)
                        else Searcher(bestEntry!!.name.componentsLower, queryLower).search(depth)
                if (result != null) {
                    val paddedResult: IntArray
                    if (depth == 0) {
                        // prepend 0s
                        paddedResult = IntArray(bestEntry.name.componentsLower.size)
                        System.arraycopy(result, 0, paddedResult, paddedResult.size - result.size, result.size)
                    } else {
                        paddedResult = result
                    }
                    yield(SearchResult(query, includedCategoriesFinal[bestList], bestEntry, paddedResult))
                    visited[bestList][indices[bestList]] = true
                }
                indices[bestList]++
            }
        }
    }

    @VisibleForTesting
    internal class Searcher(private val groups: Array<String>, private val query: String) {
        private val memo = arrayOfNulls<IntArray>(groups.size * query.length * 2)

        fun search(maxDepth: Int) = memo(0, 0, maxDepth, mustMatchStart = false)

        private fun impl(groupsI: Int, queryI: Int, maxDepth: Int, mustMatchStart: Boolean): IntArray? {
            val maxSubstringEnd = queryI + commonPrefixLength(groups[groupsI], query, 0, queryI)
            var i = queryI
            if (mustMatchStart) {
                // if mustMatchStart is true, we cannot match the empty string here.
                i++
            }
            while (i <= maxSubstringEnd) {
                var next = memo(groupsI + 1, i, if (i == queryI) maxDepth else maxDepth - 1, false)
                if (next == null && i == queryI + groups[groupsI].length) {
                    // second chance: if this group was matched fully, attempt a mustMatchStart match on the next group
                    next = memo(groupsI + 1, i, maxDepth, true)
                }
                if (next != null) {
                    next[groupsI] = i - queryI
                    return next
                }
                i++
            }
            return null
        }

        private fun memo(groupsI: Int, queryI: Int, maxDepth: Int, mustMatchStart: Boolean): IntArray? {
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

            val i = groupsI + queryI * groups.size + (if (mustMatchStart) groups.size * query.length else 0)
            val present = memo[i]
            if (present == null) {
                val computed = impl(groupsI, queryI, maxDepth, mustMatchStart)
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
                    other.entry == entry &&
                    other.match.contentEquals(match)
        }

        override fun hashCode(): Int {
            return Objects.hash(query, key, entry, match.contentHashCode())
        }
    }

    data class Input<V>(
            val string: String,
            val value: V
    )

    internal data class SplitEntry(
            val string: String
    ) : Comparable<SplitEntry> {
        val componentsLower: Array<String> = split(string).toTypedArray()

        // this is a heuristic for finding the first "class name" character. Package names are assumed to be lowercase.
        // we can't simply use the last dot because there might be nested classes
        private val simpleNameLength =
                string.length - string.indexOfFirst { Ascii.isUpperCase(it) }

        override fun compareTo(other: SplitEntry): Int {
            if (this.simpleNameLength < other.simpleNameLength) return -1
            if (this.simpleNameLength > other.simpleNameLength) return 1

            if (this.string.length < other.string.length) return -1
            if (this.string.length > other.string.length) return 1

            return this.string.compareTo(other.string)
        }
    }

    internal data class Entry<V>(
            val value: V,
            val name: SplitEntry,
            val simpleName: SplitEntry
    )
}