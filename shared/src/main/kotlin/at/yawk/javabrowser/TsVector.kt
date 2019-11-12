package at.yawk.javabrowser

import org.eclipse.collections.api.IntIterable
import org.eclipse.collections.api.collection.primitive.MutableIntCollection
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.api.list.primitive.MutableIntList
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.IntSets
import java.util.BitSet

/**
 * @author yawkat
 */
class TsVector {
    companion object {
        private const val POSITION_LIMIT = 16383
        private const val BY_LEXEME_LIMIT = 256

        val DEFAULT_WEIGHT = TsQuery.Weight.D
    }

    // values encoded weight + position
    private val map = HashMap<String, MutableIntList>()
    var size: Int = 0
        private set

    private fun encode(position: Int, weight: TsQuery.Weight) = position or (weight.ordinal shl 24)
    private fun decodePosition(value: Int) = value and 0xffffff
    private fun decodeWeight(value: Int) = TsQuery.Weight.values()[value shr 24]

    private fun list(lexeme: String) = map.getOrPut(lexeme) { IntLists.mutable.empty() }

    /**
     * @param position 0-indexed
     * @return `true` iff this lexeme was added successfully. `false` iff postgres bounds would have been exceeded.
     */
    fun add(lexeme: String, position: Int, weight: TsQuery.Weight = DEFAULT_WEIGHT): Boolean {
        if (position < 0) throw IndexOutOfBoundsException()
        if (position >= POSITION_LIMIT) return false
        val l = list(lexeme)
        if (l.size() >= BY_LEXEME_LIMIT) return false
        l.add(encode(position, weight))
        size++
        return true
    }

    fun addFromSql(s: CharSequence) {
        var i = 0

        val currentKeyBuilder = StringBuilder()
        while (i < s.length) {
            val lexemeStart = s[i++]
            if (lexemeStart == ' ') continue
            if (lexemeStart != '\'') throw IllegalArgumentException("Unexpected character: $lexemeStart")

            while (true) {
                val here = s[i++]
                if (here == '\'') {
                    if (s[i] == '\'') {
                        // escape sequence
                        s[i++]
                        currentKeyBuilder.append('\'')
                    } else {
                        // done!
                        break
                    }
                } else {
                    currentKeyBuilder.append(here)
                }
            }

            val list = list(currentKeyBuilder.toString())
            var first = true
            while (i < s.length && s[i] != ' ') {
                val positionStart = s[i++]
                if (positionStart != if (first) ':' else ',')
                    throw IllegalArgumentException("Unexpected character: $lexemeStart")
                first = false

                val numberStart = i
                while (i < s.length && s[i] in '0'..'9') {
                    i++
                }
                val numberEnd = i
                val position = s.substring(numberStart, numberEnd).toInt() - 1

                var weight = DEFAULT_WEIGHT
                if (i < s.length && s[i] in 'A'..'D') {
                    weight = TsQuery.Weight.byValue(s[i++])
                }

                list.add(encode(position, weight))
                size++
            }
            currentKeyBuilder.clear()
        }
    }

    fun toSql(): String {
        val tsVector = StringBuilder()
        for ((i, v) in map.entries.withIndex()) {
            val (key, list) = v
            if (i > 0) tsVector.append(' ')
            tsVector
                    .append('\'')
                    .append(key.replace("'", "''"))
                    .append('\'')
                    .append(':')
            for (j in 0 until list.size()) {
                if (j > 0) tsVector.append(',')
                val value = list[j]
                val position = decodePosition(value)
                val weight = decodeWeight(value)
                tsVector.append(position + 1)
                if (weight != DEFAULT_WEIGHT) {
                    tsVector.append(weight.value)
                }
            }
        }
        return tsVector.toString()
    }

    fun clear() {
        map.clear()
        size = 0
    }

    private fun TsQuery.Term.matchesLexeme(lexeme: String): Boolean {
        if (matchStart) {
            return lexeme.startsWith(term)
        } else {
            return lexeme == term
        }
    }

    /**
     * @param f (need to check weight, values) -> Unit
     */
    private inline fun forPossiblyMatching(term: TsQuery.Term,
                                           negative: Boolean = false,
                                           f: (Boolean, IntList) -> Unit) {
        if (negative || term.matchStart) {
            for ((lexeme, values) in map.entries) {
                if (negative) {
                    if (!term.matchAllWeights) {
                        f(term.matchesLexeme(lexeme), values) // can match all lexemes
                    } else {
                        if (!term.matchesLexeme(lexeme)) {
                            f(false, values)
                        }
                    }
                } else {
                    if (term.matchesLexeme(lexeme)) {
                        f(!term.matchAllWeights, values)
                    }
                }
            }
        } else {
            val entry = map[term.term]
            if (entry != null) f(!term.matchAllWeights, entry)
        }
    }

    private inline fun forMatchingPositions(term: TsQuery.Term, negative: Boolean = false, f: (Int) -> Unit) {
        forPossiblyMatching(term, negative) { needCheckWeight, values ->
            for (i in 0 until values.size()) {
                val value = values[i]
                val position = decodePosition(value)
                val weight = decodeWeight(value)
                if (!needCheckWeight || (term.weights.contains(weight) xor negative)) {
                    f(position)
                }
            }
        }
    }

    /**
     * Collect matches of [query] in this entire vector into [into]. If false is returned, [into] remains unmodified.
     *
     * @return Whether the query could be matched.
     */
    private fun collectMatches(query: TsQuery, into: MutableIntCollection?): Boolean {
        when (query) {
            is TsQuery.Negation -> {
                return !collectMatches(query.query, null)
            }
            is TsQuery.Disjunction -> {
                var any = false
                for (operand in query.operands) {
                    any = any or collectMatches(operand, into) // do not short-circuit
                }
                return any
            }
            is TsQuery.Conjunction -> {
                val collectionTarget = if (into == null) null else IntSets.mutable.empty()
                for (operand in query.operands) {
                    if (!collectMatches(operand, collectionTarget)) {
                        return false
                    }
                }
                into?.addAll(collectionTarget)
                return true
            }
            is TsQuery.Phrase -> {
                // phrases in postgres are *weeeiiirrrddd* and the results don't make a lot of sense. This is an
                // alternate approach that should work for the phrases it supports. In particular, conjunctions and
                // disjunctions inside phrases remain unimplemented. Disjunctions would probably be feasible but
                // conjunctions are a can of worms I have no intention of opening. Phrases inside negations inside
                // phrases also aren't implemented.

                // first, flatten the query.
                // "leaf" terms of this phrase
                val flatTerms = ArrayList<TsQuery.Term>()
                // whether the terms are negated
                val negative = BitSet()
                var totalDistance = 0
                // distance of each term from the start of the top phrase
                val distances = IntLists.mutable.empty()

                fun flatten(phrase: TsQuery.Phrase) {
                    for ((i, operand) in phrase.operands.withIndex()) {
                        if (i != 0) totalDistance += phrase.distance + 1
                        when (operand) {
                            is TsQuery.Phrase -> flatten(operand)
                            is TsQuery.Negation -> {
                                if (operand.query is TsQuery.Term) {
                                    negative.set(flatTerms.size)
                                    flatTerms.add(operand.query)
                                    distances.add(totalDistance)
                                } else {
                                    throw UnsupportedOperationException(
                                            "Complex terms inside negations inside phrases are not supported")
                                }
                            }
                            is TsQuery.Term -> {
                                flatTerms.add(operand)
                                distances.add(totalDistance)
                            }
                            else -> throw UnsupportedOperationException(
                                    "Conjunctions and disjunctions inside phrases are not supported")
                        }
                    }
                }

                flatten(query)

                // find an anchor that will require as few loops as possible below
                val (anchorTermIndex, anchorTerm) = flatTerms.withIndex().minBy { (i, term) ->
                    // estimate the match count for this term.
                    var termMatchCount = 0
                    forPossiblyMatching(term, negative = negative[i]) { needCheckWeight, list ->
                        if (!needCheckWeight) {
                            termMatchCount += list.size()
                        } else {
                            termMatchCount += list.count { value -> (decodeWeight(value) in term.weights) xor negative[i] }
                        }
                    }
                    termMatchCount

                } ?: return true // null when this phrase is empty. Treat as a match.

                // distance from start for the selected anchor. If the query is 'a <2> b <3> c <-> d', and the selected
                // anchor is c, this distance would be 5.
                val anchorDistanceFromStart = distances[anchorTermIndex]

                var anyMatch = false // has any anchor position matched?
                forMatchingPositions(anchorTerm, negative = negative[anchorTermIndex]) { anchorPosition ->
                    // Check if backtracking that far is possible at all
                    if (anchorPosition >= anchorDistanceFromStart) {

                        // have all terms matched for this anchor position?
                        val allMatch = flatTerms.withIndex().all { (i, term) ->
                            if (i == anchorTermIndex) return@all true // don't double-check the anchor

                            val expectedPosition = distances[i] - anchorDistanceFromStart + anchorPosition
                            var termMatch = false // has this term been found?
                            forMatchingPositions(term, negative = negative[i]) { pos ->
                                // todo: could binary search, and short-circuit
                                termMatch = termMatch or (pos == expectedPosition)
                            }
                            termMatch
                        }
                        if (allMatch) {
                            anyMatch = true
                            // add this anchor match to the output list
                            if (into != null) {
                                for (i in flatTerms.indices) {
                                    into.add(distances[i] - anchorDistanceFromStart + anchorPosition)
                                }
                            }
                        }
                    }
                }
                return anyMatch
            }
            is TsQuery.Term -> {
                var any = false
                forMatchingPositions(query) { position ->
                    into?.add(position)
                    any = true
                }
                return any
            }
        }
    }

    fun findMatchPositions(query: TsQuery): IntIterable? {
        val output = IntSets.mutable.empty()
        if (!collectMatches(query, output)) {
            return null
        }
        return output
    }

    override fun toString() = toSql()

    override fun equals(other: Any?) = other is TsVector && other.map == this.map
    override fun hashCode() = map.hashCode()
}