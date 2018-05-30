package at.yawk.javabrowser

/**
 * @author yawkat
 */
sealed class SourceAnnotation

data class BindingRef(val binding: String) : SourceAnnotation()
data class BindingDecl(val binding: String) : SourceAnnotation()
data class Style(val styleClass: Set<String>) : SourceAnnotation()

@Suppress("FunctionName")
fun Style(vararg styleClass: String) = Style(setOf(*styleClass))