package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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

data class BindingRef(val binding: String) : SourceAnnotation()
data class BindingDecl(val binding: String) : SourceAnnotation()
data class Style(val styleClass: Set<String>) : SourceAnnotation()
data class LocalVariableRef(val id: String) : SourceAnnotation()

@Suppress("FunctionName")
fun Style(vararg styleClass: String) = Style(setOf(*styleClass))