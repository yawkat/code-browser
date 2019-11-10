package at.yawk.javabrowser

import java.util.EnumSet

sealed class TsQuery {
    abstract override fun toString(): String

    data class Negation(val query: TsQuery) : TsQuery() {
        override fun toString() = "!$query"
    }

    data class Disjunction(val operands: List<TsQuery>) : TsQuery() {
        override fun toString() = operands.joinToString(separator = " | ", prefix = "(", postfix = ")")
    }

    data class Conjunction(val operands: List<TsQuery>) : TsQuery() {
        override fun toString() = operands.joinToString(separator = " & ", prefix = "(", postfix = ")")
    }

    data class Phrase(val operands: List<TsQuery>, val distance: Int = 0) : TsQuery() {
        override fun toString() = operands.joinToString(
                separator = " <${distance + 1}> ", prefix = "(", postfix = ")")
    }

    data class Term(val term: String,
                    val weights: Set<Weight> = EnumSet.allOf(Weight::class.java),
                    val matchStart: Boolean = false) : TsQuery() {
        val matchAllWeights: Boolean
            get() = weights.size == 4

        override fun toString(): String {
            var suffix: String
            if (matchAllWeights && !matchStart) {
                suffix = ""
            } else {
                suffix = ":"
                if (!matchAllWeights) {
                    suffix = weights.joinToString(prefix = ":") { it.value.toString() }
                }
                if (matchStart) {
                    suffix += "*"
                }
            }
            // escape the term
            return "'" + term.replace("'", "''") + "'" + suffix
        }
    }

    enum class Weight(val value: Char) {
        A('A'), B('B'), C('C'), D('D');

        companion object {
            fun byValue(value: Char) = values().single { it.value == value }
        }
    }
}