package at.yawk.javabrowser.server.typesearch

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
                // this used to be 0, 2, 0, 1 (a[ab]b[c]), but matching a[a]b[bc] is also valid
                intArrayOf(0, 1, 1, 1)
        )
        Assert.assertEquals(
                SearchIndex.Searcher(arrayOf("ab", "a", "b", "c"), "abc").search(2),
                // this used to be 2, 0, 0, 1 ([ab]ab[c]), but matching ab[abc] is better
                intArrayOf(0, 1, 1, 1)
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
    fun simple() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf(SearchIndex.Input("ConcurrentHashMap", Unit)).iterator(), BindingTokenizer.Java)
        val results = searchIndex.find("CHM").toList()
        Assert.assertEquals(results.size, 1)
        Assert.assertEquals(results[0].entry.name.string, "ConcurrentHashMap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 1, 1))
    }

    @Test
    fun properOrder() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf(SearchIndex.Input("ConcurrentHashMap", Unit),
                SearchIndex.Input("ConcurrentHmap", Unit)).iterator(), BindingTokenizer.Java)
        val results = searchIndex.find("CHmap").toList()
        Assert.assertEquals(results.size, 2)
        Assert.assertEquals(results[0].entry.name.string, "ConcurrentHmap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 4))
        Assert.assertEquals(results[1].entry.name.string, "ConcurrentHashMap")
        Assert.assertEquals(results[1].match, intArrayOf(1, 1, 3))
    }

    @Test
    fun multiCategory() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf(SearchIndex.Input("ConcurrentHashMap", Unit)).iterator(), BindingTokenizer.Java)
        searchIndex.replace("cat2", listOf(SearchIndex.Input("ConcurrentHmap", Unit)).iterator(), BindingTokenizer.Java)
        val results = searchIndex.find("CHmap").toList()
        Assert.assertEquals(results.size, 2)
        Assert.assertEquals(results[0].entry.name.string, "ConcurrentHmap")
        Assert.assertEquals(results[0].match, intArrayOf(1, 4))
        Assert.assertEquals(results[1].entry.name.string, "ConcurrentHashMap")
        Assert.assertEquals(results[1].match, intArrayOf(1, 1, 3))
    }

    @Test
    fun `class name first`() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf(
                SearchIndex.Input("xxxxx.LongerName", Unit),
                SearchIndex.Input("long.ShortName", Unit)
                ).iterator(), BindingTokenizer.Java)
        // `long` appears in both names, but LongerName should be listed first since it appears in the class name
        val results = searchIndex.find("long").toList()
        Assert.assertEquals(results[0].entry.name.string, "xxxxx.LongerName")
        Assert.assertEquals(results[0].match, intArrayOf(0, 4, 0))
        Assert.assertEquals(results[1].entry.name.string, "long.ShortName")
    }

    @Test
    fun `searcher - at end, complete with full remaining text`() {
        Assert.assertNotNull(
                SearchIndex.Searcher(arrayOf("my", "name"), "myname").search(1)
        )
    }

    @Test
    fun `searcher - retry with different depth`() {
        Assert.assertNotNull(
                SearchIndex.Searcher(arrayOf(
                        "compressed", "oop", "hash", "map"),
                        "cohama").search(3)
        )
    }

    @Test
    fun `at end, complete with full remaining text`() {
        val searchIndex = SearchIndex<String, Unit>()
        searchIndex.replace("cat1", listOf(
                SearchIndex.Input("myname.X", Unit),
                SearchIndex.Input("MyName", Unit) // this entry should match first
        ).iterator(), BindingTokenizer.Java)
        val results = searchIndex.find("myname").toList()
        Assert.assertEquals(results[0].entry.name.string, "MyName")
    }

    @Test
    fun `return of multiple versions`() {
        val searchIndex = SearchIndex<String, Unit>()
        for (i in 0 until 10) {
            searchIndex.replace("java/$i", listOf(SearchIndex.Input("MyName", Unit)).iterator(), BindingTokenizer.Java)
        }
        val result = searchIndex.find("MyName").toList()
        Assert.assertEquals(result.size, 10)
    }
}