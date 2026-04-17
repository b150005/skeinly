package io.github.b150005.knitnote.android.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.compose.rememberNavController
import io.github.b150005.knitnote.android.KnitNoteTheme
import io.github.b150005.knitnote.android.test.FakeProjectRepository
import io.github.b150005.knitnote.android.test.KoinTestRule
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.ui.navigation.KnitNoteNavHost
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.koin.java.KoinJavaComponent.get
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class NavigationFlowTest {
    @get:Rule
    val koinRule = KoinTestRule()

    /**
     * These tests require local-only mode (no Supabase configured).
     * Skip if Supabase credentials are present in BuildConfig.
     */
    private fun assumeLocalOnlyMode() {
        assumeFalse(
            "Supabase is configured — navigation tests require local-only mode",
            SupabaseConfig.isConfigured,
        )
    }

    @Test
    fun startDestination_isProjectList_inLocalOnlyMode() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                KnitNoteTheme {
                    val navController = rememberNavController()
                    KnitNoteNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithText("Knit Note").assertIsDisplayed()
            onNodeWithText("No projects yet").assertIsDisplayed()
        }

    @Test
    fun navigateToProfile_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                KnitNoteTheme {
                    val navController = rememberNavController()
                    KnitNoteNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithContentDescription("Profile").performClick()
            waitForIdle()

            onNodeWithText("Profile").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("Knit Note").assertIsDisplayed()
        }

    @Test
    fun navigateToProjectDetail_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            val repo = get<ProjectRepository>(ProjectRepository::class.java) as FakeProjectRepository
            val now = Clock.System.now()
            repo.addProject(
                Project(
                    id = "p1",
                    ownerId = LocalUser.ID,
                    patternId = LocalUser.DEFAULT_PATTERN_ID,
                    title = "Navigation Test Scarf",
                    status = ProjectStatus.NOT_STARTED,
                    currentRow = 0,
                    totalRows = 10,
                    startedAt = null,
                    completedAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            setContent {
                KnitNoteTheme {
                    val navController = rememberNavController()
                    KnitNoteNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithText("Navigation Test Scarf").performClick()
            waitForIdle()

            // Verify detail screen — use "of 10 rows" to confirm we're on detail
            onNodeWithText("of 10 rows").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("Knit Note").assertIsDisplayed()
        }

    @Test
    fun navigateToActivityFeed_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                KnitNoteTheme {
                    val navController = rememberNavController()
                    KnitNoteNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithContentDescription("Activity Feed").performClick()
            waitForIdle()

            onNodeWithText("Activity Feed").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("Knit Note").assertIsDisplayed()
        }

    @Test
    fun navigateToSharedWithMe_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                KnitNoteTheme {
                    val navController = rememberNavController()
                    KnitNoteNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithContentDescription("Shared With Me").performClick()
            waitForIdle()

            onNodeWithText("Shared With Me").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("Knit Note").assertIsDisplayed()
        }
}
