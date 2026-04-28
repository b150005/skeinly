package io.github.b150005.knitnote.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.b150005.knitnote.KnitNoteTheme
import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.test.FakeProjectRepository
import io.github.b150005.knitnote.test.KoinTestRule
import io.github.b150005.knitnote.ui.projectdetail.ProjectDetailScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.java.KoinJavaComponent.get
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ProjectDetailScreenTest {
    @get:Rule
    val koinRule = KoinTestRule()

    private fun seedProject(
        id: String = "p1",
        title: String = "Test Project",
        currentRow: Int = 5,
        totalRows: Int? = 20,
        status: ProjectStatus = ProjectStatus.IN_PROGRESS,
    ): Project {
        val repo = get<ProjectRepository>(ProjectRepository::class.java) as FakeProjectRepository
        val now = Clock.System.now()
        val project =
            Project(
                id = id,
                ownerId = LocalUser.ID,
                patternId = LocalUser.DEFAULT_PATTERN_ID,
                title = title,
                status = status,
                currentRow = currentRow,
                totalRows = totalRows,
                startedAt = now,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        repo.addProject(project)
        return project
    }

    @Test
    fun displaysProjectDetails() =
        runComposeUiTest {
            seedProject(title = "Cable Knit", currentRow = 10, totalRows = 50)

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithText("Cable Knit").assertIsDisplayed()
            onNodeWithTag("rowCounter").assertTextEquals("10")
            onNodeWithText("of 50 rows").assertIsDisplayed()
            onNodeWithText("In Progress").assertIsDisplayed()
        }

    @Test
    fun incrementRow_updatesCount() =
        runComposeUiTest {
            seedProject(currentRow = 5, totalRows = 20)

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithTag("rowCounter").assertTextEquals("5")

            onNodeWithText("+").performClick()
            waitForIdle()

            onNodeWithTag("rowCounter").assertTextEquals("6")
        }

    @Test
    fun decrementRow_updatesCount() =
        runComposeUiTest {
            seedProject(currentRow = 5, totalRows = 20)

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithTag("rowCounter").assertTextEquals("5")

            onNodeWithText("-").performClick()
            waitForIdle()

            onNodeWithTag("rowCounter").assertTextEquals("4")
        }

    @Test
    fun notStartedProject_showsMarkCompleteButton() =
        runComposeUiTest {
            seedProject(status = ProjectStatus.NOT_STARTED, currentRow = 0, totalRows = null)

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithText("Mark Complete").assertIsDisplayed()
            onNodeWithText("Not Started").assertIsDisplayed()
        }

    @Test
    fun completedProject_showsReopenButton() =
        runComposeUiTest {
            seedProject(status = ProjectStatus.COMPLETED, currentRow = 20, totalRows = 20)

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithText("Reopen").assertIsDisplayed()
            onNodeWithText("Completed!").assertIsDisplayed()
        }

    @Test
    fun emptyNotes_showsNoNotesYet() =
        runComposeUiTest {
            seedProject()

            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithText("Notes").assertIsDisplayed()
            onNodeWithText("No notes yet").assertIsDisplayed()
        }

    @Test
    fun backButton_callsCallback() =
        runComposeUiTest {
            seedProject()

            var backCalled = false
            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "p1", onBack = { backCalled = true })
                }
            }

            waitForIdle()
            onNodeWithContentDescription("Back").performClick()
            assertTrue("Expected back callback to be called", backCalled)
        }

    @Test
    fun projectNotFound_showsErrorMessage() =
        runComposeUiTest {
            setContent {
                KnitNoteTheme {
                    ProjectDetailScreen(projectId = "nonexistent", onBack = {})
                }
            }

            waitForIdle()
            onNodeWithText("Project not found").assertIsDisplayed()
        }
}
