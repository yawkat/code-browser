package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
value class BindingId @JsonCreator constructor(@get:JsonValue val hash: Long) {
    companion object {
        /**
         * The top-level "" package has this fixed binding id
         */
        val TOP_LEVEL_PACKAGE = BindingId(-1)
    }
}
