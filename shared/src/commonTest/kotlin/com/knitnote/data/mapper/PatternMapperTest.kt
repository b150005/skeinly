package com.knitnote.data.mapper

import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals

class PatternMapperTest {

    @Test
    fun `Difficulty toDbString round-trips`() {
        Difficulty.entries.forEach { difficulty ->
            val dbString = difficulty.toDbString()
            val parsed = when (dbString) {
                "beginner" -> Difficulty.BEGINNER
                "intermediate" -> Difficulty.INTERMEDIATE
                "advanced" -> Difficulty.ADVANCED
                else -> error("Unknown")
            }
            assertEquals(difficulty, parsed)
        }
    }

    @Test
    fun `Visibility toDbString round-trips`() {
        Visibility.entries.forEach { visibility ->
            val dbString = visibility.toDbString()
            val parsed = when (dbString) {
                "private" -> Visibility.PRIVATE
                "shared" -> Visibility.SHARED
                "public" -> Visibility.PUBLIC
                else -> error("Unknown")
            }
            assertEquals(visibility, parsed)
        }
    }
}
