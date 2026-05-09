package io.github.b150005.skeinly.ui.notifications

import app.cash.turbine.test
import io.github.b150005.skeinly.notifications.FakeNotificationPermissionPrompter
import io.github.b150005.skeinly.notifications.NotificationPermissionStatus
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPermissionViewModelTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_reads_os_status_via_queryPermissionStatus() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.GRANTED)
            assertEquals(NotificationPermissionStatus.GRANTED, rig.viewModel.state.value.osStatus)
        }

    @Test
    fun trigger_with_not_determined_and_should_prompt_shows_explainer() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
            assertTrue(rig.viewModel.state.value.isExplainerVisible)
        }

    @Test
    fun trigger_with_already_prompted_does_not_show_explainer() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            rig.prompter.setPrompted(true)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_DETAIL_OPENED,
                ),
            )
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
        }

    @Test
    fun trigger_with_granted_does_not_show_explainer() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.GRANTED)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
        }

    @Test
    fun trigger_with_denied_does_not_show_explainer() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.DENIED)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
        }

    @Test
    fun user_accepted_explainer_records_asked_and_requests_permission() =
        runTest {
            val rig =
                makeRig(
                    initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED,
                    requestStatus = NotificationPermissionStatus.GRANTED,
                )
            rig.prompter.setPrompted(false)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
            rig.viewModel.onEvent(NotificationPermissionEvent.UserAcceptedExplainer)
            assertEquals(1, rig.prompter.permissionAskedCount)
            assertEquals(1, rig.requestPermissionCallCount)
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
        }

    @Test
    fun user_accepted_with_grant_calls_registerForPushNotifications() =
        runTest {
            val rig =
                makeRig(
                    initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED,
                    requestStatus = NotificationPermissionStatus.GRANTED,
                )
            rig.viewModel.onEvent(NotificationPermissionEvent.UserAcceptedExplainer)
            assertEquals(1, rig.registerCallCount)
            assertEquals("en-US", rig.registerCalledWithLocale)
        }

    @Test
    fun user_accepted_with_denied_does_not_call_registerForPushNotifications() =
        runTest {
            val rig =
                makeRig(
                    initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED,
                    requestStatus = NotificationPermissionStatus.DENIED,
                )
            rig.viewModel.onEvent(NotificationPermissionEvent.UserAcceptedExplainer)
            assertEquals(0, rig.registerCallCount)
            assertEquals(NotificationPermissionStatus.DENIED, rig.viewModel.state.value.osStatus)
        }

    @Test
    fun user_dismissed_explainer_records_dismiss_and_hides_explainer() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            rig.viewModel.onEvent(
                NotificationPermissionEvent.TriggerEncountered(
                    NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                ),
            )
            rig.viewModel.onEvent(NotificationPermissionEvent.UserDismissedExplainer)
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
            assertEquals(1, rig.prompter.dismissedTriggers.size)
        }

    @Test
    fun open_os_settings_event_invokes_open_callback() =
        runTest {
            val rig = makeRig()
            rig.viewModel.onEvent(NotificationPermissionEvent.OpenOsSettingsRequested)
            assertEquals(1, rig.openOsSettingsCallCount)
        }

    @Test
    fun refresh_status_re_reads_os_state() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            rig.queryStatus = NotificationPermissionStatus.GRANTED
            rig.viewModel.onEvent(NotificationPermissionEvent.RefreshStatus)
            assertEquals(NotificationPermissionStatus.GRANTED, rig.viewModel.state.value.osStatus)
        }

    @Test
    fun query_permission_status_failure_keeps_current_state() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.GRANTED)
            rig.queryThrowsOnNext = true
            rig.viewModel.onEvent(NotificationPermissionEvent.RefreshStatus)
            assertEquals(NotificationPermissionStatus.GRANTED, rig.viewModel.state.value.osStatus)
        }

    @Test
    fun explainer_visible_state_emits_in_order() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            rig.viewModel.state.test {
                val initial = awaitItem()
                assertFalse(initial.isExplainerVisible)
                rig.viewModel.onEvent(
                    NotificationPermissionEvent.TriggerEncountered(
                        NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS,
                    ),
                )
                val withExplainer = awaitItem()
                assertTrue(withExplainer.isExplainerVisible)
                rig.viewModel.onEvent(NotificationPermissionEvent.UserDismissedExplainer)
                val afterDismiss = awaitItem()
                assertFalse(afterDismiss.isExplainerVisible)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun register_for_push_notifications_locale_param_overridable() =
        runTest {
            val rig =
                makeRig(
                    initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED,
                    requestStatus = NotificationPermissionStatus.GRANTED,
                    locale = "ja-JP",
                )
            rig.viewModel.onEvent(NotificationPermissionEvent.UserAcceptedExplainer)
            assertEquals("ja-JP", rig.registerCalledWithLocale)
        }

    @Test
    fun explainer_initially_hidden_when_pristine() =
        runTest {
            val rig = makeRig(initialQueryStatus = NotificationPermissionStatus.NOT_DETERMINED)
            assertFalse(rig.viewModel.state.value.isExplainerVisible)
            assertNull(null)
        }

    private class TestRig {
        val prompter: FakeNotificationPermissionPrompter = FakeNotificationPermissionPrompter()
        var queryStatus: NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED
        var requestStatus: NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED
        var queryThrowsOnNext: Boolean = false
        var requestPermissionCallCount: Int = 0
        var registerCallCount: Int = 0
        var registerCalledWithLocale: String? = null
        var openOsSettingsCallCount: Int = 0
        lateinit var viewModel: NotificationPermissionViewModel
    }

    private fun makeRig(
        initialQueryStatus: NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED,
        requestStatus: NotificationPermissionStatus = NotificationPermissionStatus.NOT_DETERMINED,
        locale: String = "en-US",
    ): TestRig {
        val rig = TestRig()
        rig.queryStatus = initialQueryStatus
        rig.requestStatus = requestStatus
        rig.viewModel =
            NotificationPermissionViewModel(
                prompter = rig.prompter,
                queryPermissionStatus = {
                    if (rig.queryThrowsOnNext) {
                        rig.queryThrowsOnNext = false
                        throw RuntimeException("test")
                    }
                    rig.queryStatus
                },
                requestPermission = {
                    rig.requestPermissionCallCount += 1
                    rig.requestStatus
                },
                registerForPushNotifications = { localeArg ->
                    rig.registerCallCount += 1
                    rig.registerCalledWithLocale = localeArg
                    null
                },
                openOsSettings = { rig.openOsSettingsCallCount += 1 },
                locale = locale,
            )
        return rig
    }
}
