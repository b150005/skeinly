package io.github.b150005.knitnote.ui.components

import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.label_difficulty_advanced
import io.github.b150005.knitnote.generated.resources.label_difficulty_beginner
import io.github.b150005.knitnote.generated.resources.label_difficulty_intermediate
import kotlin.test.Test
import kotlin.test.assertEquals

class DifficultyLabelsTest {
    @Test
    fun beginner_maps_to_label_difficulty_beginner() {
        assertEquals(Res.string.label_difficulty_beginner, Difficulty.BEGINNER.labelKey)
    }

    @Test
    fun intermediate_maps_to_label_difficulty_intermediate() {
        assertEquals(Res.string.label_difficulty_intermediate, Difficulty.INTERMEDIATE.labelKey)
    }

    @Test
    fun advanced_maps_to_label_difficulty_advanced() {
        assertEquals(Res.string.label_difficulty_advanced, Difficulty.ADVANCED.labelKey)
    }

    @Test
    fun labelKey_is_distinct_per_difficulty_case() {
        val keys = Difficulty.entries.map { it.labelKey }.toSet()
        assertEquals(Difficulty.entries.size, keys.size)
    }
}
