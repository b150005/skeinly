package io.github.b150005.skeinly.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.compose.rememberNavController
import io.github.b150005.skeinly.SkeinlyTheme
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.model.ProjectStatus
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import io.github.b150005.skeinly.test.FakeProjectRepository
import io.github.b150005.skeinly.test.KoinTestRule
import io.github.b150005.skeinly.ui.navigation.SkeinlyNavHost
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
                SkeinlyTheme {
                    val navController = rememberNavController()
                    SkeinlyNavHost(navController = navController)
                }
            }

            waitForIdle()
            // The brand wordmark "Skeinly" is intentionally absent from the
            // ProjectList AppBar after Sprint A (the title is empty so the
            // overflow `MoreVert` button has uncontested horizontal space).
            // Anchor on the empty-state copy instead.
            onNodeWithText("No projects yet").assertIsDisplayed()
        }

    @Test
    fun navigateToProfile_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                SkeinlyTheme {
                    val navController = rememberNavController()
                    SkeinlyNavHost(navController = navController)
                }
            }

            waitForIdle()
            // ProjectList nav now uses an overflow `MoreVert` menu (Sprint A).
            // Open the menu first, then tap the Profile entry by its testTag.
            onNodeWithTag("moreMenu").performClick()
            waitForIdle()
            onNodeWithTag("profileButton").performClick()
            waitForIdle()

            onNodeWithText("Profile").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("No projects yet").assertIsDisplayed()
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
                SkeinlyTheme {
                    val navController = rememberNavController()
                    SkeinlyNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithText("Navigation Test Scarf").performClick()
            waitForIdle()

            // Verify detail screen — use "of 10 rows" to confirm we're on detail
            onNodeWithText("of 10 rows").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            // ProjectList no longer renders a "Skeinly" title; the project
            // card itself is the back-navigation anchor.
            onNodeWithText("Navigation Test Scarf").assertIsDisplayed()
        }

    @Test
    fun navigateToActivityFeed_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                SkeinlyTheme {
                    val navController = rememberNavController()
                    SkeinlyNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithTag("moreMenu").performClick()
            waitForIdle()
            onNodeWithTag("activityFeedButton").performClick()
            waitForIdle()

            onNodeWithText("Activity Feed").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("No projects yet").assertIsDisplayed()
        }

    @Test
    fun navigateToSharedWithMe_andBack() =
        runComposeUiTest {
            assumeLocalOnlyMode()

            setContent {
                SkeinlyTheme {
                    val navController = rememberNavController()
                    SkeinlyNavHost(navController = navController)
                }
            }

            waitForIdle()
            onNodeWithTag("moreMenu").performClick()
            waitForIdle()
            onNodeWithTag("sharedWithMeButton").performClick()
            waitForIdle()

            onNodeWithText("Shared With Me").assertIsDisplayed()

            onNodeWithContentDescription("Back").performClick()
            waitForIdle()

            onNodeWithText("No projects yet").assertIsDisplayed()
        }
}
