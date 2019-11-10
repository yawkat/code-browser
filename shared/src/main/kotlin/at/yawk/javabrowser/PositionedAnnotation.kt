package at.yawk.javabrowser

/**
 * @author yawkat
 */
data class PositionedAnnotation(
        val start: Int,
        val length: Int,
        val annotation: SourceAnnotation
) {
    val end: Int
        get() = start + length
}