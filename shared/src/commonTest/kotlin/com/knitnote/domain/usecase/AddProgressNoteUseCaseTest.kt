package com.knitnote.domain.usecase

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddProgressNoteUseCaseTest {

    private val repository = FakeProgressRepository()
    private val useCase = AddProgressNoteUseCase(repository)

    @Test
    fun `creates progress note with correct projectId and rowNumber`() = runTest {
        val result = useCase(projectId = "proj-1", rowNumber = 5, note = "Decrease stitch")

        assertEquals("proj-1", result.projectId)
        assertEquals(5, result.rowNumber)
        assertEquals("Decrease stitch", result.note)
    }

    @Test
    fun `generates unique id for each note`() = runTest {
        val first = useCase(projectId = "proj-1", rowNumber = 1, note = "Note A")
        val second = useCase(projectId = "proj-1", rowNumber = 2, note = "Note B")

        assertTrue(first.id.isNotBlank())
        assertTrue(first.id != second.id)
    }

    @Test
    fun `sets createdAt to current time`() = runTest {
        val before = Clock.System.now()
        val result = useCase(projectId = "proj-1", rowNumber = 1, note = "Test")
        val after = Clock.System.now()

        assertTrue(result.createdAt >= before && result.createdAt <= after)
    }

    @Test
    fun `stores note in repository`() = runTest {
        useCase(projectId = "proj-1", rowNumber = 3, note = "Color change")

        val stored = repository.getByProjectId("proj-1")
        assertEquals(1, stored.size)
        assertEquals("Color change", stored.first().note)
    }

    @Test
    fun `sets photoUrl to null`() = runTest {
        val result = useCase(projectId = "proj-1", rowNumber = 1, note = "Test")

        assertEquals(null, result.photoUrl)
    }
}
