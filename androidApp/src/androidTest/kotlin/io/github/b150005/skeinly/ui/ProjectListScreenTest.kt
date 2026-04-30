package io.github.b150005.skeinly.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import io.github.b150005.skeinly.SkeinlyTheme
import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.model.ProjectStatus
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import io.github.b150005.skeinly.test.FakeProjectRepository
import io.github.b150005.skeinly.test.KoinTestRule
import io.github.b150005.skeinly.ui.projectlist.ProjectListScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.java.KoinJavaComponent.get
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ProjectListScreenTest {
    @get:Rule
    val koinRule = KoinTestRule()

    private fun seedProject(
        id: String = "p1",
        title: String = "Test Project",
        currentRow: Int = 0,
        totalRows: Int? = null,
        status: ProjectStatus = ProjectStatus.NOT_STARTED,
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
                startedAt = null,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        repo.addProject(project)
        return project
    }

    @Test
    fun emptyState_displaysNoProjectsMessage() =
        runComposeUiTest {
            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = {})
                }
            }

            waitForIdle()
            onNodeWithText("No projects yet").assertIsDisplayed()
            onNodeWithText("Tap + to create your first project").assertIsDisplayed()
        }

    @Test
    fun fab_opensCreateDialog() =
        runComposeUiTest {
            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = {})
                }
            }

            waitForIdle()
            onNodeWithContentDescription("New Project").performClick()
            waitForIdle()
            onNodeWithText("Project Name").assertIsDisplayed()
            onNodeWithText("Total Rows (optional)").assertIsDisplayed()
        }

    @Test
    fun createProject_appearsInList() =
        runComposeUiTest {
            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = {})
                }
            }

            waitForIdle()
            onNodeWithContentDescription("New Project").performClick()
            waitForIdle()

            onNodeWithText("Project Name").performTextInput("My Scarf")
            onNodeWithText("Create").performClick()

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("My Scarf").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("My Scarf").assertIsDisplayed()
        }

    @Test
    fun projectWithProgress_displaysCorrectly() =
        runComposeUiTest {
            seedProject(
                title = "Cable Knit Sweater",
                currentRow = 15,
                totalRows = 100,
                status = ProjectStatus.IN_PROGRESS,
            )

            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = {})
                }
            }

            waitForIdle()
            onNodeWithText("Cable Knit Sweater").assertIsDisplayed()
            onNodeWithText("In Progress").assertIsDisplayed()
            onNodeWithText("15 / 100 rows").assertIsDisplayed()
        }

    @Test
    fun clickProject_callsCallback() =
        runComposeUiTest {
            seedProject(id = "p1", title = "Test Project")

            var clickedId: String? = null
            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = { clickedId = it })
                }
            }

            waitForIdle()
            onNodeWithText("Test Project").performClick()
            assertEquals("p1", clickedId)
        }

    @Test
    fun createDialog_cancelDismisses() =
        runComposeUiTest {
            setContent {
                SkeinlyTheme {
                    ProjectListScreen(onProjectClick = {})
                }
            }

            waitForIdle()
            onNodeWithContentDescription("New Project").performClick()
            waitForIdle()
            onNodeWithText("Project Name").assertIsDisplayed()

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            onNodeWithText("No projects yet").assertIsDisplayed()
        }
}
