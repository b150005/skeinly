package io.github.b150005.knitnote.ui.chart

import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer

/**
 * Bounded snapshot ring buffer used by [ChartEditorViewModel] to back undo/redo.
 *
 * Each [Snapshot] is a full copy of the editable payload (extents + layers). The
 * buffer is capped at [capacity] (default 50) — once full, the oldest undo entry
 * is evicted. Recording a new snapshot clears the redo stack, matching common
 * editor semantics where branching after an undo drops the alternative future.
 *
 * Full snapshots (instead of command-diffs) are intentional for Phase 32 MVP:
 * the current editable payload is small and a commit-rooted history graph will
 * arrive in Phase 37.
 */
class EditHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    data class Snapshot(
        val extents: ChartExtents,
        val layers: List<ChartLayer>,
    )

    private val undoStack: ArrayDeque<Snapshot> = ArrayDeque()
    private val redoStack: ArrayDeque<Snapshot> = ArrayDeque()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(snapshot: Snapshot) {
        if (undoStack.size >= capacity) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
    }

    /**
     * Pops the most recent snapshot and returns it. [current] is pushed onto the
     * redo stack so [redo] can restore it. Returns `null` when nothing to undo.
     */
    fun undo(current: Snapshot): Snapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        return previous
    }

    /**
     * Pops from the redo stack, pushing [current] back onto the undo stack.
     * Returns `null` when nothing to redo.
     */
    fun redo(current: Snapshot): Snapshot? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        return next
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 50
    }
}
