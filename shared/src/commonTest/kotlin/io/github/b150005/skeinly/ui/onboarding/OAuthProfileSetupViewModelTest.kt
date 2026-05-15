package io.github.b150005.skeinly.ui.onboarding

import io.github.b150005.skeinly.domain.model.AuthProviderKind
import io.github.b150005.skeinly.domain.model.OAuthOnboardingMetadata
import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.UseCaseError
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Phase 26.6 (ADR-022 §6.6) — locks the OAuthProfileSetupViewModel
 * state machine. Tests use lambda stubs to inject [importAvatar] +
 * [saveDisplayName] + [markGateCompleted] without standing up the
 * Ktor + Storage + AuthRepository stack; production wiring routes
 * through the real use cases at the DI site.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OAuthProfileSetupViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sampleUser =
        User(
            id = "user-1",
            displayName = "Alice",
            avatarUrl = null,
            bio = null,
            createdAt = Clock.System.now(),
        )

    private fun metadataWithPicture() =
        OAuthOnboardingMetadata(
            displayName = "Alice From Google",
            pictureUrl = "https://lh3.googleusercontent.com/a/test",
            primaryProvider = AuthProviderKind.Google,
        )

    private fun metadataWithoutPicture() =
        OAuthOnboardingMetadata(
            displayName = "Bob From Apple",
            pictureUrl = null,
            primaryProvider = AuthProviderKind.Apple,
        )

    @Test
    fun `initial state seeds displayName and pictureUrl from metadata`() =
        runTest {
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("ignored") },
                    saveDisplayName = { _, _ -> UseCaseResult.Success(sampleUser) },
                    markGateCompleted = {},
                )
            val state = vm.state.value
            assertEquals("Alice From Google", state.displayName)
            assertEquals("https://lh3.googleusercontent.com/a/test", state.pictureUrl)
            assertFalse(state.avatarImported)
            assertFalse(state.isSubmitting)
            assertNull(state.error)
        }

    @Test
    fun `UpdateDisplayName replaces state and clears error`() =
        runTest {
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("ignored") },
                    saveDisplayName = { _, _ ->
                        UseCaseResult.Failure(UseCaseError.Network(IllegalStateException("oops")))
                    },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            // surface an error first
            assertEquals(ErrorMessage.NetworkUnavailable, vm.state.value.error)
            vm.onEvent(OAuthProfileSetupEvent.UpdateDisplayName("Charlie"))
            assertEquals("Charlie", vm.state.value.displayName)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `UseOAuthAvatar success flips avatarImported`() =
        runTest {
            val importedUrls = mutableListOf<String>()
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { url ->
                        importedUrls += url
                        UseCaseResult.Success("https://supabase/avatars/user-1/profile/oauth-avatar.jpg")
                    },
                    saveDisplayName = { _, _ -> UseCaseResult.Success(sampleUser) },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
            assertTrue(vm.state.value.avatarImported)
            assertFalse(vm.state.value.isImportingAvatar)
            assertEquals(listOf("https://lh3.googleusercontent.com/a/test"), importedUrls)
        }

    @Test
    fun `UseOAuthAvatar failure surfaces error and leaves avatarImported false`() =
        runTest {
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { _ ->
                        UseCaseResult.Failure(UseCaseError.ImageInvalid)
                    },
                    saveDisplayName = { _, _ -> UseCaseResult.Success(sampleUser) },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
            assertFalse(vm.state.value.avatarImported)
            assertEquals(ErrorMessage.ImageInvalid, vm.state.value.error)
        }

    @Test
    fun `ChooseDifferentAvatar resets avatarImported flag`() =
        runTest {
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("anything") },
                    saveDisplayName = { _, _ -> UseCaseResult.Success(sampleUser) },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
            assertTrue(vm.state.value.avatarImported)
            vm.onEvent(OAuthProfileSetupEvent.ChooseDifferentAvatar)
            assertFalse(vm.state.value.avatarImported)
        }

    @Test
    fun `Submit empty name surfaces FieldRequired and does not call save`() =
        runTest {
            var saveCalled = false
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("x") },
                    saveDisplayName = { _, _ ->
                        saveCalled = true
                        UseCaseResult.Success(sampleUser)
                    },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UpdateDisplayName("   "))
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            assertEquals(ErrorMessage.FieldRequired, vm.state.value.error)
            assertFalse(saveCalled)
        }

    @Test
    fun `Submit success calls markGateCompleted and emits Completed nav event`() =
        runTest {
            var markCalled = false
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithoutPicture(),
                    importAvatar = { UseCaseResult.Success("x") },
                    saveDisplayName = { name, avatar ->
                        assertEquals("Bob From Apple", name)
                        assertNull(avatar)
                        UseCaseResult.Success(sampleUser)
                    },
                    markGateCompleted = { markCalled = true },
                )
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            val event = vm.navEvents.first()
            assertEquals(OAuthProfileSetupNavEvent.Completed, event)
            assertTrue(markCalled)
            assertFalse(vm.state.value.isSubmitting)
        }

    @Test
    fun `Submit forwards imported avatar URL through to saveDisplayName`() =
        runTest {
            var lastAvatar: String? = null
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("https://imported/avatar.jpg") },
                    saveDisplayName = { _, avatar ->
                        lastAvatar = avatar
                        UseCaseResult.Success(sampleUser)
                    },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            vm.navEvents.first()
            assertEquals("https://imported/avatar.jpg", lastAvatar)
        }

    @Test
    fun `Submit failure surfaces error and skips markGateCompleted`() =
        runTest {
            var markCalled = false
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("x") },
                    saveDisplayName = { _, _ ->
                        UseCaseResult.Failure(UseCaseError.Network(RuntimeException("offline")))
                    },
                    markGateCompleted = { markCalled = true },
                )
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            assertEquals(ErrorMessage.NetworkUnavailable, vm.state.value.error)
            assertFalse(markCalled)
            assertFalse(vm.state.value.isSubmitting)
        }

    @Test
    fun `Skip marks gate completed even without saving displayName`() =
        runTest {
            var markCalled = false
            var saveCalled = false
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { UseCaseResult.Success("x") },
                    saveDisplayName = { _, _ ->
                        saveCalled = true
                        UseCaseResult.Success(sampleUser)
                    },
                    markGateCompleted = { markCalled = true },
                )
            vm.onEvent(OAuthProfileSetupEvent.Skip)
            val event = vm.navEvents.first()
            assertEquals(OAuthProfileSetupNavEvent.Completed, event)
            assertTrue(markCalled)
            assertFalse(saveCalled)
        }

    @Test
    fun `Submit re-entry while in-flight is ignored`() =
        runTest {
            var callCount = 0
            val gate = CompletableDeferred<Unit>()
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithoutPicture(),
                    importAvatar = { UseCaseResult.Success("x") },
                    saveDisplayName = { _, _ ->
                        callCount++
                        gate.await()
                        UseCaseResult.Success(sampleUser)
                    },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.Submit)
            vm.onEvent(OAuthProfileSetupEvent.Submit) // ignored
            gate.complete(Unit)
            vm.navEvents.first()
            assertEquals(1, callCount)
        }

    @Test
    fun `ClearError drops the active ErrorMessage`() =
        runTest {
            val vm =
                OAuthProfileSetupViewModel(
                    metadata = metadataWithPicture(),
                    importAvatar = { _ -> UseCaseResult.Failure(UseCaseError.ImageInvalid) },
                    saveDisplayName = { _, _ -> UseCaseResult.Success(sampleUser) },
                    markGateCompleted = {},
                )
            vm.onEvent(OAuthProfileSetupEvent.UseOAuthAvatar)
            assertEquals(ErrorMessage.ImageInvalid, vm.state.value.error)
            vm.onEvent(OAuthProfileSetupEvent.ClearError)
            assertNull(vm.state.value.error)
        }
}
