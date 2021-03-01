package at.yawk.javabrowser

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * A realm describes a global name space for source files, bindings, references and so on. References by default target
 * the same realm. The purpose of this is to avoid mixing source references with bytecode references too much.
 */
enum class Realm(@get:JsonValue val id: Byte) {
    SOURCE(0),
    BYTECODE(1);

    companion object {
        private val values = values()

        fun parse(name: String) = when {
            name.equals("source", ignoreCase = true) -> SOURCE
            name.equals("bytecode", ignoreCase = true) -> BYTECODE
            else -> null
        }

        @JsonCreator
        fun byId(id: Byte) = values.single { it.id == id }
    }
}