package at.yawk.javabrowser

/**
 * A realm describes a global name space for source files, bindings, references and so on. References by default target
 * the same realm. The purpose of this is to avoid mixing source references with bytecode references too much.
 */
enum class Realm(val id: Byte) {
    SOURCE(0),
    BYTECODE(1),
}