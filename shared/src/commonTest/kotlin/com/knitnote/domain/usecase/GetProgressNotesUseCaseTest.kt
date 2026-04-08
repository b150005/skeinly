package com.knitnote.domain.usecase

import app.cash.turbine.test
import com.knitnote.domain.model.Progress
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetProgressNotesUseCaseTest {

    private val repository = FakeProgressRepository()
    private val useCase = GetProgressNotesUseCase(repository)

    @Test
    fun `returns empty flow when no notes exist`() = runTest {
        useCase(projectId = "proj-1").test {
            val notes = awaitItem()
            assertTrue(notes.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `returns notes for specific project`() = runTest {
        val note = Progress(
            id = "note-1",
            projectId = "proj-1",
            rowNumber = 3,
            photoUrl = null,
            note = "Decrease stitch",
            createdAt = Clock.System.now(),
        )
        repository.create(note)

        useCase(projectId = "proj-1").test {
            val notes = awaitItem()
            assertEquals(1, notes.size)
            assertEquals("Decrease stitch", notes.first().note)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `does not return notes from other projects`() = runTest {
        repository.create(
            Progress(
                id = "note-1",
                projectId = "proj-1",
                rowNumber = 1,
                photoUrl = null,
                note = "Note for proj-1",
                createdAt = Clock.System.now(),
            )
        )
        repository.create(
            Progress(
                id = "note-2",
                projectId = "proj-2",
                rowNumber = 1,
                photoUrl = null,
                note = "Note for proj-2",
                createdAt = Clock.System.now(),
            )
        )

        useCase(projectId = "proj-1").test {
            val notes = awaitItem()
            assertEquals(1, notes.size)
            assertEquals("proj-1", notes.first().projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emits updated list when note is added`() = runTest {
        useCase(projectId = "proj-1").test {
            assertEquals(0, awaitItem().size)

            repository.create(
                Progress(
                    id = "note-1",
                    projectId = "proj-1",
                    rowNumber = 1,
                    photoUrl = null,
                    note = "New note",
                    createdAt = Clock.System.now(),
                )
            )

            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
