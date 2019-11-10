package at.yawk.javabrowser

import at.yawk.javabrowser.Tokenizer.tokenizeEnglish
import org.apache.commons.lang3.StringEscapeUtils
import org.testng.Assert
import org.testng.annotations.Test
import kotlin.io.readText
import kotlin.sequences.any
import kotlin.sequences.toList
import kotlin.text.contains
import kotlin.text.indices
import kotlin.text.toCharArray

/**
 * @author yawkat
 */
class TokenizerTest {
    @Test
    fun `unescaping reader`() {
        val escaped = """\u0123 \uaaFF \n \b \f \t lorem ipsum \33 \033 \777"""
        val reader = Tokenizer.UnescapingSplittingReader(escaped.toCharArray(), true)
        val unescaped = reader.readText()
        Assert.assertEquals(unescaped, StringEscapeUtils.unescapeJava(escaped))
        for (i in unescaped.indices) {
            val mapped = reader.translateOutputToRawPosition(i)
            if (escaped[mapped] != '\\') {
                Assert.assertEquals(escaped[mapped], unescaped[i])
            }
        }
    }

    @Test
    fun english() {
        val text = "The quick brown fox jumps over the lazy dog"
        val tokens = sequence<Tokenizer.Token> {
            tokenizeEnglish(0, text.toCharArray())
        }
        Assert.assertEquals(
                tokens.toList(),
                listOf(
                        Tokenizer.Token("quick", 4, 5, false),
                        Tokenizer.Token("brown", 10, 5, false),
                        Tokenizer.Token("fox", 16, 3, false),
                        Tokenizer.Token("jump", 20, 5, false),
                        Tokenizer.Token("over", 26, 4, false),
                        Tokenizer.Token("lazi", 35, 4, false),
                        Tokenizer.Token("dog", 40, 3, false)
                )
        )
    }

    @Test
    fun `english escapes`() {
        val text = " b\\u0072own "
        val tokens = sequence<Tokenizer.Token> {
            tokenizeEnglish(0, text.toCharArray())
        }
        Assert.assertEquals(
                tokens.toList(),
                listOf(Tokenizer.Token("brown", 1, text.length - 2, false))
        )
    }

    @Test
    fun `english camel case`() {
        val text = "urlEncoder"
        val tokens = sequence<Tokenizer.Token> {
            tokenizeEnglish(0, text.toCharArray())
        }
        Assert.assertEquals(
                tokens.toList(),
                listOf(Tokenizer.Token("url", 0, 3, false),
                        Tokenizer.Token("encod", 3, 7, false))
        )
    }

    @Test
    fun `english camel case abbreviation`() {
        val text = "URLEncoder"
        val tokens = sequence<Tokenizer.Token> {
            tokenizeEnglish(0, text.toCharArray())
        }
        Assert.assertEquals(
                tokens.toList(),
                listOf(Tokenizer.Token("url", 0, 3, false),
                        Tokenizer.Token("encod", 3, 7, false))
        )
    }

    @Test
    fun `java camel case`() {
        val text = "class Abc { void testMethod() {} }"
        val tokens = Tokenizer.tokenize(text)
        Assert.assertEquals(
                tokens.toList(),
                listOf(Tokenizer.Token("class", 0, 5, false),
                        Tokenizer.Token("abc", 6, 3, false),
                        Tokenizer.Token("{", 10, 1, true),
                        Tokenizer.Token("void", 12, 4, false),
                        Tokenizer.Token("test", 17, 4, false),
                        Tokenizer.Token("method", 21, 6, false),
                        Tokenizer.Token("(", 27, 1, true),
                        Tokenizer.Token(")", 28, 1, true),
                        Tokenizer.Token("{", 30, 1, true),
                        Tokenizer.Token("}", 31, 1, true),
                        Tokenizer.Token("}", 33, 1, true))
        )
    }

    @Test
    fun `number identifier`() {
        val text = "abc1abc"
        val tokens = Tokenizer.tokenize(text)
        Assert.assertEquals(
                tokens.toList(),
                listOf(Tokenizer.Token("abc", 0, 3, false),
                        Tokenizer.Token("1", 3, 1, false),
                        Tokenizer.Token("abc", 4, 3, false))
        )
    }

    @Test
    fun `java nul escapes`() {
        val text = "class Abc { char c = '\\0'; String d = '\\u0000'; }"
        val tokens = Tokenizer.tokenize(text)
        Assert.assertFalse(tokens.any { it.text.contains(0.toChar()) })
    }

    @Test
    fun `error token`() {
        // illegal java, should still tokenize.
        val text = "#abcdef"
        val tokens = Tokenizer.tokenize(text)
        Assert.assertEquals(tokens.toList(), listOf(
                Tokenizer.Token("#", 0, 1, true),
                Tokenizer.Token("abcdef", 1, 6, false)
        ))
    }
}