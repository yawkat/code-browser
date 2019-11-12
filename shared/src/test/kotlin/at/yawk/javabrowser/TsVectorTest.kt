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
    private infix fun TsQuery.then(other: TsQuery) = this.then(0, other)
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

    @Test
    fun bugTest() {
        val sqlVector = "'compos':14 'number':9 'len':56,60,64,84,91 'doesn''if':40,50,59,63,80 '11':34 'byte':66,69,92 'integ':81 'memori':88 'singl':67,73,78 'except':29,46 'max_valu':82 'valu':57,74 'out':87 'throw':26,42,85 'maximum':99 'return':6,12,52,62,75 'argument':28,45 'coder':79 'zero':3,25 'neg':32,48 'exceed':98 'new':43,68,76,86 'count':2,8,18,24,31,39,41,47,49,51,61,70,83,94 'this':53 'fill':72 'final':54,65 'produc':96 'sinc':33 'code':17,30 'string':1,5,13,15,21,22,36,77,93,97 'arrai':71 'error':89 'public':35 'param':7 'repeat':11,16,37,90 'length':58 'int':38,55 'illeg':27,44 'time':10,19,95 'empti':1,4,20,23 'size':100"
        val query = TsQuery.Phrase(listOf(
                TsQuery.Term("produc"),
                TsQuery.Term("string"),
                TsQuery.Term("exceed"),
                TsQuery.Term("maximum"),
                TsQuery.Term("size")))
        val vector = TsVector()
        vector.addFromSql(sqlVector)

        Assert.assertEquals(vector.findMatchPositions(query), IntSets.immutable.of(95, 96, 97, 98, 99))
    }
}