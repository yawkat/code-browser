package at.yawk.javabrowser.server

import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
@Suppress("RemoveRedundantBackticks")
class SearchIndexTest {
    @Test
    fun `searcher - finds match`() {
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("a", "b", "ab", "c"), "abc").search(2),
                intArrayOf(0, 0, 2, 1)
        )
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("a", "ab", "b", "c"), "abc").search(2),
                intArrayOf(0, 2, 0, 1)
        )
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("ab", "a", "b", "c"), "abc").search(2),
                intArrayOf(2, 0, 0, 1)
        )
    }

    @Test
    fun `searcher - backtrack`() {
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("a", "ab", "bc", "x"), "abcx").search(3),
                intArrayOf(0, 1, 2, 1) // or intArrayOf(1, 0, 2, 1)
        )
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("a", "ab", "bc", "x"), "abcy").search(3),
                null
        )
        // TODO
    }

    @Test
    fun split() {
        Assert.assertEquals(SearchIndex.split("ConcurrentHashMap"), listOf("concurrent", "hash", "map"))
        Assert.assertEquals(SearchIndex.split("java.util"), listOf("java.", "util"))
        Assert.assertEquals(SearchIndex.split("URI"), listOf("uri"))
        Assert.assertEquals(SearchIndex.split("Jsr320"), listOf("jsr", "320"))
        Assert.assertEquals(SearchIndex.split("JSR320"), listOf("jsr", "320"))
    }

    @Test
    fun simple() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf("ConcurrentHashMap" to Unit).iterator())
        val results = searchIndex.find("CHM").toList()
        Assert.assertEquals(results.size, 1)
        Assert.assertEquals(results[0].entry.string, "ConcurrentHashMap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 1, 1))
    }

    @Test
    fun properOrder() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf("ConcurrentHashMap" to Unit, "ConcurrentHmap" to Unit).iterator())
        val results = searchIndex.find("CHmap").toList()
        Assert.assertEquals(results.size, 2)
        Assert.assertEquals(results[0].entry.string, "ConcurrentHmap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 4))
        Assert.assertEquals(results[1].entry.string, "ConcurrentHashMap")
        Assert.assertEquals(results[1].match, intArrayOf(1, 1, 3))
    }

    @Test
    fun multiCategory() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf("ConcurrentHashMap" to Unit).iterator())
        searchIndex.replace("cat2", listOf("ConcurrentHmap" to Unit).iterator())
        val results = searchIndex.find("CHmap").toList()
        Assert.assertEquals(results.size, 2)
        Assert.assertEquals(results[0].entry.string, "ConcurrentHmap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 4))
        Assert.assertEquals(results[1].entry.string, "ConcurrentHashMap")
        Assert.assertEquals(results[1].match, intArrayOf(1, 1, 3))
    }
}