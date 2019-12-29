package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * @author yawkat
 */
data class PositionedAnnotation(
        val start: Int,
        val length: Int,
        val annotation: SourceAnnotation
) {
    @get:JsonIgnore
    val end: Int
        get() = start + length
}