package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue

/**
 * @author yawkat
 */
@JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
@JsonSubTypes(value = [
    JsonSubTypes.Type(value = BindingRef::class, name = "binding-ref"),
    JsonSubTypes.Type(value = BindingDecl::class, name = "binding-decl"),
    JsonSubTypes.Type(value = Style::class, name = "style"),
    JsonSubTypes.Type(value = LocalVariableRef::class, name = "lv-ref")
])
sealed class SourceAnnotation

data class BindingRef(
        val type: BindingRefType,
        val binding: String,
        /**
         * ID of this ref in this source file.
         */
        val id: Int
) : SourceAnnotation()

enum class BindingRefType(@get:JsonValue val id: Int) {
    UNCLASSIFIED(0),
    SUPER_CONSTRUCTOR_CALL(1),
    SUPER_METHOD_CALL(2),
    METHOD_CALL(3),
    FIELD_ACCESS(4),
    SUPER_TYPE(5),
    SUPER_METHOD(6),
    JAVADOC(7),
    RETURN_TYPE(8),
    LOCAL_VARIABLE_TYPE(9),
    PARAMETER_TYPE(10),
    FIELD_TYPE(11);

    companion object {
        @JsonCreator
        @JvmStatic
        fun byId(id: Int) = values().single { it.id == id }
    }
}

data class BindingDecl(val binding: String, val superBindings: List<Super> = emptyList()) : SourceAnnotation() {
    data class Super(
            val name: String,
            val binding: String
    )
}
data class Style(val styleClass: Set<String>) : SourceAnnotation()
data class LocalVariableRef(val id: String) : SourceAnnotation()

@Suppress("FunctionName")
fun Style(vararg styleClass: String) = Style(setOf(*styleClass))