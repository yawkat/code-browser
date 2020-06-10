package at.yawk.javabrowser.server.typesearch

import org.testng.Assert
import org.testng.annotations.Test

class BindingTokenizerTest {
    @Test
    fun java() {
        Assert.assertEquals(BindingTokenizer.Java.tokenize("ConcurrentHashMap"), listOf("concurrent", "hash", "map"))
        Assert.assertEquals(BindingTokenizer.Java.tokenize("java.util"), listOf("java.", "util"))
        Assert.assertEquals(BindingTokenizer.Java.tokenize("URI"), listOf("uri"))
        Assert.assertEquals(BindingTokenizer.Java.tokenize("Jsr320"), listOf("jsr", "320"))
        Assert.assertEquals(BindingTokenizer.Java.tokenize("JSR320"), listOf("jsr", "320"))
    }
}