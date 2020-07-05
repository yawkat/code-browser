package at.yawk.javabrowser.generator

import at.yawk.javabrowser.IntRangeSet
import at.yawk.javabrowser.Style

/**
 * @author yawkat
 */
internal object KeywordHandler {
    private val normal = listOf(
            "abstract", "continue", "for", "new", "switch", "assert", "default", "if", "package", "synchronized",
            "boolean", "do", "goto", "private", "this", "break", "double", "implements", "protected", "throw", "byte",
            "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient", "catch",
            "extends", "int", "short", "try", "char", "final", "interface", "static", "void", "class", "finally",
            "long", "strictfp", "volatile", "const", "float", "native", "super", "while", "true", "false", "null"
    )
    private val module = listOf(
            "open", "module", "requires", "transitive", "exports", "opens", "to", "uses", "provides", "with"
    )

    private val regex = mapOf(
            BindingVisitor.SourceFileType.REGULAR to normal,
            BindingVisitor.SourceFileType.MODULE_INFO to normal + module,
            BindingVisitor.SourceFileType.PACKAGE_INFO to normal
    ).mapValues { "\\b(${it.value.joinToString("|")})\\b".toPattern() }

    private val KEYWORD_STYLE = Style("keyword")

    fun annotateKeywords(annotatedSourceFile: GeneratorSourceFile,
                         disable: IntRangeSet,
                         sourceFileType: BindingVisitor.SourceFileType) {
        val matcher = regex.getValue(sourceFileType).matcher(annotatedSourceFile.text)
        while (matcher.find()) {
            if (!disable.contains(matcher.start())) {
                annotatedSourceFile.annotate(matcher.start(), matcher.end() - matcher.start(), KEYWORD_STYLE)
            }
        }
    }
}