package com.knitnote.domain.usecase

import com.knitnote.domain.model.Progress
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AddProgressNoteUseCaseTest {

    private val repository = FakeProgressRepository()
    private val useCase = AddProgressNoteUseCase(repository)

    @Test
    fun `creates progress note with correct projectId and rowNumber`() = runTest {
        val result = assertIs<UseCaseResult.Success<Progress>>(
            useCase(projectId = "proj-1", rowNumber = 5, note = "Decrease stitch"),
        )

        assertEquals("proj-1", result.value.projectId)
        assertEquals(5, result.value.rowNumber)
        assertEquals("Decrease stitch", result.value.note)
    }

    @Test
    fun `generates unique id for each note`() = runTest {
        val first = assertIs<UseCaseResult.Success<Progress>>(
            useCase(projectId = "proj-1", rowNumber = 1, note = "Note A"),
        )
        val second = assertIs<UseCaseResult.Success<Progress>>(
            useCase(projectId = "proj-1", rowNumber = 2, note = "Note B"),
        )

        assertTrue(first.value.id.isNotBlank())
        assertTrue(first.value.id != second.value.id)
    }

    @Test
    fun `sets createdAt to current time`() = runTest {
        val before = Clock.System.now()
        val result = assertIs<UseCaseResult.Success<Progress>>(
            useCase(projectId = "proj-1", rowNumber = 1, note = "Test"),
        )
        val after = Clock.System.now()

        assertTrue(result.value.createdAt >= before && result.value.createdAt <= after)
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
        val result = assertIs<UseCaseResult.Success<Progress>>(
            useCase(projectId = "proj-1", rowNumber = 1, note = "Test"),
        )

        assertEquals(null, result.value.photoUrl)
    }

    @Test
    fun `blank note returns Validation error`() = runTest {
        val result = useCase(projectId = "proj-1", rowNumber = 1, note = "  ")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `empty note returns Validation error`() = runTest {
        val result = useCase(projectId = "proj-1", rowNumber = 1, note = "")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }
}
