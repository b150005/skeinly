package com.knitnote.android.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.compose.rememberNavController
import com.knitnote.android.KnitNoteTheme
import com.knitnote.android.test.FakeProjectRepository
import com.knitnote.android.test.KoinTestRule
import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.ui.navigation.KnitNoteNavHost
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
