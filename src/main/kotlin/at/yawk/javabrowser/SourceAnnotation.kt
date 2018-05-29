package at.yawk.javabrowser

import org.jsoup.nodes.Node

/**
 * @author yawkat
 */
sealed class SourceAnnotation

data class TypeRef(val binaryName: String) : SourceAnnotation()