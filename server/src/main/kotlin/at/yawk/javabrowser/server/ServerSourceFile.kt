package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.PositionedAnnotation
import com.fasterxml.jackson.core.JsonToken
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * @author yawkat
 */
class ServerSourceFile(val text: String, annotations: Sequence<PositionedAnnotation>) {
    var annotations: Sequence<PositionedAnnotation> = annotations
        private set
    val declarations: Iterator<BindingDecl>
        get() = annotations.mapNotNull { it.annotation as? BindingDecl }.iterator()

    constructor(
            textBytes: ByteArray,
            annotationBytes: ByteArray
    ) : this(textBytes.toString(StandardCharsets.UTF_8), lazyParseAnnotations(annotationBytes))

    @Deprecated("try to use #entries instead")
    val annotationList by lazy(LazyThreadSafetyMode.NONE) {
        val l = annotations.toList()
        this.annotations = l.asSequence()
        l
    }

    fun bakeAnnotations() {
        annotationList
    }

    companion object {
        fun lazyParseAnnotations(annotationBytes: ByteArray) = sequence {
            val parser = cborMapper.factory.createParser(annotationBytes)
            if (parser.nextToken() != JsonToken.START_ARRAY) throw IOException()
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                yield(cborMapper.readValue(parser, PositionedAnnotation::class.java)!!)
            }
            if (parser.nextToken() != null) throw IOException("trailing tokens")
        }
    }
}