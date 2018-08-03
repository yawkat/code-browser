package at.yawk.javabrowser

import com.google.common.collect.RangeSet
import org.intellij.lang.annotations.Language

/**
 * @author yawkat
 */
object KeywordHandler {
    @Language("RegExp")
    private val regex = "\\b(abstract|continue|for|new|switch|assert|default|if|package|synchronized|boolean|do|goto|private|this|break|double|implements|protected|throw|byte|else|import|public|throws|case|enum|instanceof|return|transient|catch|extends|int|short|try|char|final|interface|static|void|class|finally|long|strictfp|volatile|const|float|native|super|while)\\b".toPattern()

    fun annotateKeywords(annotatedSourceFile: AnnotatedSourceFile, disable: RangeSet<Int>) {
        val matcher = regex.matcher(annotatedSourceFile.text)
        while (matcher.find()) {
            if (!disable.contains(matcher.start())) {
                annotatedSourceFile.annotate(matcher.start(), matcher.end() - matcher.start(),
                        Style("keyword"))
            }
        }
    }
}