package at.yawk.javabrowser

import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.testng.Assert
import org.testng.annotations.DataProvider
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class TsVectorTest {
    @Test
    fun `to sql`() {
        val vector = TsVector()
        vector.add("abc", 0)
        vector.add("def", 1)
        vector.add("abc", 2, TsQuery.Weight.A)
        vector.add("q'i", 3)

        val expected = """'abc':1,3A 'def':2 'q''i':4"""
        // transform to set since order isn't guaranteed
        Assert.assertEquals(vector.toSql().split(' ').toSet(), expected.split(' ').toSet())
    }

    @Test(dependsOnMethods = ["to sql"])
    fun `from sql`() {
        val vector = TsVector()
        vector.add("abc", 0)
        vector.add("def", 1)
        vector.add("abc", 2, TsQuery.Weight.A)
        vector.add("q'i", 3)

        val parsed = TsVector()
        parsed.addFromSql(vector.toSql())
        Assert.assertEquals(parsed, vector)
    }

    private fun simpleVector(s: String): TsVector {
        val vector = TsVector()
        for ((i, c) in s.split(' ').withIndex()) {
            vector.add(c, i)
        }
        return vector
    }

    private infix fun TsQuery.and(other: TsQuery) = TsQuery.Conjunction(listOf(this, other))
    private infix fun TsQuery.or(other: TsQuery) = TsQuery.Disjunction(listOf(this, other))
    private infix fun TsQuery.then(other: TsQuery) = this.then(1, other)
    private fun TsQuery.then(distance: Int, other: TsQuery) = TsQuery.Phrase(listOf(this, other), distance)
    private operator fun TsQuery.not() = TsQuery.Negation(this)

    @DataProvider
    fun matches(): Array<Array<Any?>> = arrayOf(
            arrayOf("a b c d", TsQuery.Term("b") then TsQuery.Term("c"), IntSets.immutable.of(1, 2)),
            arrayOf("a b c d", TsQuery.Term("a") and TsQuery.Term("d"), IntSets.immutable.of(0, 3)),
            arrayOf("a b c d", TsQuery.Term("a") and TsQuery.Term("e"), null),
            arrayOf("a b c d", TsQuery.Term("a") or TsQuery.Term("e"), IntSets.immutable.of(0)),
            arrayOf("a b c d", TsQuery.Term("a") or TsQuery.Term("d"), IntSets.immutable.of(0, 3)),
            arrayOf("a bc d", TsQuery.Term("b", matchStart = true), IntSets.immutable.of(1)),
            arrayOf("a bc d", TsQuery.Term("c", matchStart = true), null),
            arrayOf("a b c d", !TsQuery.Term("e"), IntSets.immutable.empty()),
            arrayOf("a b c d", TsQuery.Term("b") then !TsQuery.Term("e"), IntSets.immutable.of(1, 2)),
            arrayOf("a b c d", TsQuery.Term("b") then !TsQuery.Term("c", weights = setOf(TsQuery.Weight.A)), IntSets.immutable.of(1, 2)),
            arrayOf("a b c d", TsQuery.Term("b") then !TsQuery.Term("c", weights = setOf(TsQuery.Weight.D)), null)
    )

    @Test(dataProvider = "matches")
    fun `test vector`(vector: String, query: TsQuery, result: IntSet?) {
        Assert.assertEquals(
                simpleVector(vector).findMatchPositions(query),
                result
        )
    }
}