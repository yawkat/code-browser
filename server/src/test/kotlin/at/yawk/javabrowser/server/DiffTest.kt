package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.DeclarationNode
import com.google.common.base.Equivalence
import org.testng.Assert
import org.testng.annotations.Test

class DiffTest {
    @Test
    fun `test buildDiffEdits + mapEdits`() {
        val newItems = listOf("a", "a", "b", "d", "e")
        val oldItems = listOf("a", "b", "c", "e")
        val edits = buildDiffEdits(
            newItems,
            oldItems,
            Equivalence.equals()
        )
        val mapped = mapEdits(newItems, oldItems, edits) { a, b -> a to b }
        Assert.assertEquals(
            mapped,
            listOf(
                "a" to DeclarationNode.DiffResult.UNCHANGED,
                "a" to DeclarationNode.DiffResult.INSERTION,
                "b" to DeclarationNode.DiffResult.UNCHANGED,
                "c" to DeclarationNode.DiffResult.DELETION,
                "d" to DeclarationNode.DiffResult.INSERTION,
                "e" to DeclarationNode.DiffResult.UNCHANGED
            )
        )
    }
}