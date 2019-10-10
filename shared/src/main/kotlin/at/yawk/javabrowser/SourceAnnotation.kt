package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import java.lang.AssertionError

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

enum class BindingRefType(@get:JsonValue val id: Int, val displayName: String) {
    UNCLASSIFIED(0, "Unclassified"),
    SUPER_CONSTRUCTOR_CALL(1, "Super constructor call"),
    SUPER_METHOD_CALL(2, "Super method call"),
    METHOD_CALL(3, "Method call"),
    @Deprecated("Replaced by FIELD_READ and FIELD_WRITE") FIELD_ACCESS(4, "Field access"),
    FIELD_READ(27, "Field read"),
    FIELD_WRITE(28, "Field write"),
    FIELD_READ_WRITE(29, "Field read+write"),
    SUPER_TYPE(5, "Super type"),
    SUPER_METHOD(6, "Super method"),
    JAVADOC(7, "Javadoc"),
    RETURN_TYPE(8, "Return type"),
    LOCAL_VARIABLE_TYPE(9, "Local variable type"),
    PARAMETER_TYPE(10, "Parameter type"),
    FIELD_TYPE(11, "Field type"),
    TYPE_CONSTRAINT(12, "Type constraint"),
    INSTANCE_OF(13, "instanceof"),
    CAST(14, "Cast type"),
    IMPORT(15, "Import"),
    ON_DEMAND_IMPORT(30, "On-Demand Import"),
    ANNOTATION_TYPE(16, "Annotation type"),
    CONSTRUCTOR_CALL(17, "Constructor call"),
    THROWS_DECLARATION(18, "Throws declaration"),
    STATIC_MEMBER_QUALIFIER(19, "Static member qualifier"),
    NESTED_CLASS_QUALIFIER(26, "Nested class qualifier"),
    METHOD_REFERENCE_RECEIVER_TYPE(20, "Method ref receiver"),
    TYPE_PARAMETER(21, "Type parameter"),
    WILDCARD_BOUND(22, "Wildcard bound"),
    THIS_REFERENCE_QUALIFIER(23, "this reference qualifier"),
    SUPER_REFERENCE_QUALIFIER(24, "super reference qualifier"),
    ANNOTATION_MEMBER_VALUE(25, "Annotation member value"),
    PACKAGE_DECLARATION(31, "Package declaration");

    companion object {
        private val byId: List<BindingRefType?>

        init {
            val byId = ArrayList<BindingRefType?>()
            for (value in values()) {
                while (value.id >= byId.size) byId.add(null)
                if (byId[value.id] != null) throw AssertionError("id conflict: $value, ${byId[value.id]}")
                byId[value.id] = value
            }
            this.byId = byId
        }

        @JsonCreator
        @JvmStatic
        fun byId(id: Int) = byId[id]!!

        fun byIdOrNull(id: Int) = byId[id]
    }
}

data class BindingDecl(
        val binding: String,
        /**
         * Parent binding of this declaration. `null` for top-level classes and packages.
         */
        val parent: String?,
        val description: Description,
        /**
         * Modifier set as listed in the jvms and in `org.eclipse.jdt.core.dom.Modifier`.
         *
         * On top of the jvms modifiers, this may include:
         *
         * - `org.eclipse.jdt.core.dom.Modifier.DEFAULT`
         * - `MODIFIER_DEPRECATED`
         * - `MODIFIER_LOCAL`
         * - `MODIFIER_ANONYMOUS`
         */
        val modifiers: Int,

        val superBindings: List<Super> = emptyList()
) : SourceAnnotation() {
    @JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
    @JsonSubTypes(value = [
        JsonSubTypes.Type(value = Description.Type::class, name = "type"),
        JsonSubTypes.Type(value = Description.Lambda::class, name = "lambda"),
        JsonSubTypes.Type(value = Description.Initializer::class, name = "initializer"),
        JsonSubTypes.Type(value = Description.Method::class, name = "method"),
        JsonSubTypes.Type(value = Description.Field::class, name = "field"),
        JsonSubTypes.Type(value = Description.Package::class, name = "package")
    ])
    sealed class Description {
        data class Type(
                val kind: Kind,
                val binding: String?,
                val simpleName: String,
                val typeParameters: List<Type> = emptyList()
        ) : Description() {
            enum class Kind(@get:JsonValue val id: Int) {
                CLASS(0),
                EXCEPTION(1),
                INTERFACE(2),
                ANNOTATION(3),
                ENUM(4);

                companion object {
                    @JsonCreator
                    @JvmStatic
                    fun byId(id: Int) = when (id) {
                        0 -> CLASS
                        1 -> EXCEPTION
                        2 -> INTERFACE
                        3 -> ANNOTATION
                        4 -> ENUM
                        else -> throw NoSuchElementException("$id")
                    }
                }
            }
        }

        data class Lambda(
                val implementingMethodBinding: Method,
                val implementingTypeBinding: Type
        ) : Description()

        object Initializer : Description() {
            @JvmStatic
            @JsonCreator
            fun create() = Initializer
        }

        data class Method(
                val name: String,
                val returnTypeBinding: Type,
                val parameterTypeBindings: List<Type>
        ) : Description()

        data class Field(
                val name: String,
                val typeBinding: Type
        ) : Description()

        object Package : Description() {
            @JvmStatic
            @JsonCreator
            fun create() = Initializer
        }
    }

    data class Super(
            val name: String,
            val binding: String
    )

    companion object {
        const val MODIFIER_DEPRECATED: Int = 1 shl 31
        const val MODIFIER_LOCAL: Int = 1 shl 30
        const val MODIFIER_ANONYMOUS: Int = 1 shl 29
    }
}
data class Style(val styleClass: Set<String>) : SourceAnnotation()
data class LocalVariableRef(val id: String) : SourceAnnotation()

@Suppress("FunctionName")
fun Style(vararg styleClass: String) = Style(setOf(*styleClass))