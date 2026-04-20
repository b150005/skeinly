package io.github.b150005.knitnote.ui.chart

import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditHistoryTest {
    private fun snapshot(cellCount: Int): EditHistory.Snapshot =
        EditHistory.Snapshot(
            extents = ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
            layers =
                listOf(
                    ChartLayer(
                        id = "L1",
                        name = "Main",
                        cells = (0 until cellCount).map { ChartCell(symbolId = "jis.knit.k", x = it, y = 0) },
                    ),
                ),
        )

    @Test
    fun `new history reports no undo and no redo`() {
        val history = EditHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `record pushes snapshot and enables undo, clears redo`() {
        val history = EditHistory()
        history.record(snapshot(1))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undo returns previous snapshot and enables redo`() {
        val history = EditHistory()
        val s1 = snapshot(1)
        history.record(s1)

        val current = snapshot(2)
        val restored = history.undo(current)
        assertEquals(s1, restored)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `redo reapplies the tip state`() {
        val history = EditHistory()
        val s1 = snapshot(1)
        val s2 = snapshot(2)
        history.record(s1)
        val undone = history.undo(s2)!!
        assertEquals(s1, undone)

        val reapplied = history.redo(undone)
        assertEquals(s2, reapplied)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undo on empty stack returns null without touching redo`() {
        val history = EditHistory()
        val current = snapshot(1)
        assertNull(history.undo(current))
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `redo past tip returns null`() {
        val history = EditHistory()
        history.record(snapshot(1))
        assertNull(history.redo(snapshot(2)))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `record after undo clears redo stack`() {
        val history = EditHistory()
        history.record(snapshot(1))
        history.record(snapshot(2))
        history.undo(snapshot(3))
        assertTrue(history.canRedo)

        history.record(snapshot(4))
        assertFalse(history.canRedo)
    }

    @Test
    fun `buffer capped at 50 entries evicts oldest`() {
        val history = EditHistory(capacity = 50)
        repeat(51) { i -> history.record(snapshot(i)) }

        // Walk all 50 undos; the 51st should return null because the oldest was evicted.
        var current = snapshot(99)
        var count = 0
        while (true) {
            val previous = history.undo(current) ?: break
            current = previous
            count++
        }
        assertEquals(50, count)
    }
}
