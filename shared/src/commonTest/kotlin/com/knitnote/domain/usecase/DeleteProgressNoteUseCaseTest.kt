package com.knitnote.domain.usecase

import com.knitnote.domain.model.Progress
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteProgressNoteUseCaseTest {

    private val repository = FakeProgressRepository()
    private val useCase = DeleteProgressNoteUseCase(repository)

    @Test
    fun `deletes existing note by id`() = runTest {
        repository.create(
            Progress(
                id = "note-1",
                projectId = "proj-1",
                rowNumber = 3,
                photoUrl = null,
                note = "To delete",
                createdAt = Clock.System.now(),
            )
        )

        useCase(progressId = "note-1")

        assertNull(repository.getById("note-1"))
    }

    @Test
    fun `does not affect other notes when deleting`() = runTest {
        repository.create(
            Progress(
                id = "note-1",
                projectId = "proj-1",
                rowNumber = 1,
                photoUrl = null,
                note = "Keep",
                createdAt = Clock.System.now(),
            )
        )
        repository.create(
            Progress(
                id = "note-2",
                projectId = "proj-1",
                rowNumber = 2,
                photoUrl = null,
                note = "Delete",
                createdAt = Clock.System.now(),
            )
        )

        useCase(progressId = "note-2")

        val remaining = repository.getByProjectId("proj-1")
        assertEquals(1, remaining.size)
        assertEquals("note-1", remaining.first().id)
    }

    @Test
    fun `does not throw when deleting non-existent note`() = runTest {
        useCase(progressId = "non-existent")

        assertTrue(repository.getByProjectId("proj-1").isEmpty())
    }
}
