package at.yawk.javabrowser.server.typesearch

import at.yawk.numaec.BumpPointerFileAllocator
import at.yawk.numaec.BumpPointerRegionAllocator
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import com.google.common.collect.Iterators
import java.io.Serializable
import java.nio.file.Path
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * @author yawkat
 */
private const val MAX_DEPTH = 5
private val NO_MATCH_SENTINEL = IntArray(0)

class SearchIndex<K, V>(
        /**
         * @see IndexAutomaton
         */
        val chunkSize: Int = 512,
        val storageDir: Path? = null
) {
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

    private val categories = ConcurrentHashMap<K, Category<V>>()

    fun replace(categoryKey: K, strings: Iterator<Input<V>>) {
        val old = categories.put(categoryKey, Category(strings))
        old?.byDepth?.forEach { it.close() }
        old?.allocator?.close()
    }

    fun find(query: String, includedCats: Set<K> = this.categories.keys) = sequence {
        val queryLower = query.toLowerCase(Locale.US)
        val categoriesFiltered = ArrayList<Category<V>>()
        val keys = ArrayList<K>()
        for (cat in includedCats) {
            categoriesFiltered.add(categories[cat] ?: continue)
            keys.add(cat)
        }

        val returnedNames = HashSet<Entry<V>>()

        for (depth in 0..MAX_DEPTH) {
            val depthIterators = categoriesFiltered.map { Iterators.peekingIterator(it.byDepth[depth].run(queryLower)) }
            while (true) {
                val bestIndex = depthIterators.indices
                        .filter { depthIterators[it].hasNext() }
                        .minBy { depthIterators[it].peek().name } ?: break
                val bestEntry = depthIterators[bestIndex].next()
                if (returnedNames.add(bestEntry)) {
                    val name = if (depth == 0) bestEntry.simpleName else bestEntry.name
                    val highlight = Searcher(name.componentsLower, queryLower)
                            // TODO: we get better highlighting with depth - 1 under some circumstances. investigate
                            .search(if (depth == 0) 1 else depth)
                            ?: throw AssertionError("Mismatch in automaton")
                    val paddedResult: IntArray
                    if (depth == 0) {
                        // prepend 0s
                        paddedResult = IntArray(bestEntry.name.componentsLower.size)
                        System.arraycopy(highlight, 0, paddedResult, paddedResult.size - highlight.size, highlight.size)
                    } else {
                        paddedResult = highlight
                    }
                    yield(SearchResult(query, keys[bestIndex], bestEntry, paddedResult))
                }
            }
        }
    }

    private inner class Category<V>(strings: Iterator<Input<V>>) {
        val allocator = if (storageDir != null) BumpPointerFileAllocator.fromTempDirectory(storageDir) else null
        val byDepth: List<IndexAutomaton<Entry<V>>>

        init {
            val entries = strings.asSequence().map {
                Entry(it.value, SplitEntry(it.string), SplitEntry(it.string.substring(it.string.lastIndexOf('.') + 1)))
            }.sortedBy { it.name }.toList()

            val entriesForQualified = entries.sortedBy { it.name }
            val entriesForSimple = entries.sortedBy { it.simpleName }
            val alignedAllocator = if (allocator != null)
                BumpPointerRegionAllocator.builder(allocator)
                        .regionSize(4 * 1024 * 1024)
                        .align(4096)
                        .build()
            else null
            byDepth = listOf(
                    IndexAutomaton(entriesForSimple,
                            { it.simpleName.componentsLower.asList() },
                            0,
                            chunkSize,
                            alignedAllocator)) +
                    (0 until MAX_DEPTH).map { jumps ->
                        IndexAutomaton(entriesForQualified,
                                { it.name.componentsLower.asList() },
                                jumps,
                                chunkSize,
                                alignedAllocator)
                    }
        }
    }

    /**
     * This class used to be used for searching, but now the filtering part is done by [IndexAutomaton] and this class
     * only does a second pass for highlighting.
     */
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

    data class SplitEntry(
            val string: String
    ) : Comparable<SplitEntry>, Serializable {
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

    data class Entry<V>(
            val value: V,
            /**
             * Qualified name of this entry
             */
            val name: SplitEntry,
            /**
             * Simple name of this entry, from the last dot. If this is a nested class, the outer class is not included.
             */
            val simpleName: SplitEntry
    )
}