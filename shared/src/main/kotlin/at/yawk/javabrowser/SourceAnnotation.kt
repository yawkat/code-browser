package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
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
    JsonSubTypes.Type(value = LocalVariableOrLabelRef::class, name = "lv-ref"),
    JsonSubTypes.Type(value = SourceLineRef::class, name = "line-ref"),
    JsonSubTypes.Type(value = RenderedJavadoc::class, name = "rendered-javadoc")
])
sealed class SourceAnnotation

data class BindingRef(
        val type: BindingRefType,
        val binding: BindingId,
        /**
         * ID of this ref in this source file.
         */
        val id: Int,
        /**
         * If `true`, this ref is a redundant with another ref nearby and should not be listed when searching for
         * references to the target.
         */
        @JsonIgnore val duplicate: Boolean = false
) : SourceAnnotation() {
    companion object {
        @JvmStatic
        @JsonCreator
        fun jacksonCreator(
                type: BindingRefType,
                binding: Long,
                id: Int,
                duplicate: Boolean = false
        ) = BindingRef(type, BindingId(binding), id, duplicate)
    }
}

enum class BindingRefType(@get:JsonValue val id: Int, val displayName: String) {
    UNCLASSIFIED(0, "Unclassified"),
    SUPER_CONSTRUCTOR_CALL(1, "Super constructor call"),
    SUPER_METHOD_CALL(2, "Super method call"),
    METHOD_CALL(3, "Method call"),
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
    PACKAGE_DECLARATION(31, "Package declaration"),
    MEMBER_REFERENCE_QUALIFIER(32, "Type qualifier for member reference"),
    ENCLOSING_METHOD(33, "Enclosing method"),
    NEST_MEMBER(34, "Nest member"),
    NEST_HOST(35, "Nest host"),
    INNER_CLASS(36, "Inner class"),
    INDY_TYPE(37, "invokedynamic type"),
    INDY_BOOTSTRAP(38, "invokedynamic bootstrap method"),
    MAIN_CLASS(39, "Main class"),
    SPI_PROVIDER(40, "SPI provider"),
    ;

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
        val id: BindingId,
        val binding: String,
        val parent: BindingId?,
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
    companion object {
        @JvmStatic
        @JsonCreator
        fun jacksonCreator(
                           id: Long,
                           binding: String,
                           parent: Long?,
                           description: Description,
                           modifiers: Int,
                           superBindings: List<Super> = emptyList()
        ) = BindingDecl(BindingId(id), binding, parent?.let { BindingId(it) }, description, modifiers, superBindings)

        const val MODIFIER_DEPRECATED: Int = 1 shl 31
        const val MODIFIER_LOCAL: Int = 1 shl 30
        const val MODIFIER_ANONYMOUS: Int = 1 shl 29
    }

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
                val binding: BindingId?,
                val simpleName: String,
                val typeParameters: List<Type> = emptyList()
        ) : Description() {
            companion object {
                @JvmStatic
                @JsonCreator
                fun jacksonCreator(
                        kind: Kind,
                        binding: Long?,
                        simpleName: String,
                        typeParameters: List<Type> = emptyList()
                ) = Type(kind, binding?.let { BindingId(it) }, simpleName, typeParameters)
            }

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
            val binding: BindingId
    ) {
        companion object {
            @JvmStatic
            @JsonCreator
            fun jacksonCreator(name: String, binding: Long) = Super(name, BindingId(binding))
        }
    }
}

data class Style(val styleClass: Set<String>) : SourceAnnotation()

@Suppress("FunctionName")
fun Style(vararg styleClass: String) = Style(setOf(*styleClass))

data class LocalVariableOrLabelRef(val id: String) : SourceAnnotation()

/**
 * @param line 1-indexed
 */
data class SourceLineRef(val sourceFile: String, val line: Int) : SourceAnnotation()

data class RenderedJavadoc(val html: String) : SourceAnnotation() {
    companion object {
        /**
         * HTML attribute on anchor elements that holds the binding ID. Replaced by a href to that binding when
         * displayed.
         */
        const val ATTRIBUTE_BINDING_ID = "data-binding"

        fun bindingToAttributeValue(bindingId: BindingId) =
            java.lang.Long.toHexString(bindingId.hash)!!

        fun attributeValueToBinding(value: String) =
            BindingId(java.lang.Long.parseUnsignedLong(value, 16))
    }
}
