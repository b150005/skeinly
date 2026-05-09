package io.github.b150005.skeinly.notifications

import io.github.b150005.skeinly.notifications.NotificationPromptTrigger.PR_COMMENT_POSTED
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger.PR_DETAIL_OPENED
import io.github.b150005.skeinly.notifications.NotificationPromptTrigger.PR_LIST_INCOMING_WITH_PRS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPermissionPrompterImplTest {
    @Test
    fun shouldPrompt_returns_true_initially_for_pr_list_trigger() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        assertTrue(prompter.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
    }

    @Test
    fun shouldPrompt_returns_true_initially_for_pr_detail_trigger() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        assertTrue(prompter.shouldPrompt(PR_DETAIL_OPENED))
    }

    @Test
    fun shouldPrompt_returns_true_initially_for_pr_comment_trigger() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        assertTrue(prompter.shouldPrompt(PR_COMMENT_POSTED))
    }

    @Test
    fun recordInAppDismiss_makes_shouldPrompt_return_false_for_same_trigger() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        prompter.recordInAppDismiss(PR_LIST_INCOMING_WITH_PRS)
        assertFalse(prompter.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
    }

    @Test
    fun recordInAppDismiss_makes_shouldPrompt_return_false_for_other_triggers() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        prompter.recordInAppDismiss(PR_LIST_INCOMING_WITH_PRS)
        assertFalse(prompter.shouldPrompt(PR_DETAIL_OPENED))
        assertFalse(prompter.shouldPrompt(PR_COMMENT_POSTED))
    }

    @Test
    fun recordPermissionAsked_makes_shouldPrompt_return_false_for_all_triggers() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        prompter.recordPermissionAsked()
        assertFalse(prompter.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
        assertFalse(prompter.shouldPrompt(PR_DETAIL_OPENED))
        assertFalse(prompter.shouldPrompt(PR_COMMENT_POSTED))
    }

    @Test
    fun recordInAppDismiss_after_recordPermissionAsked_keeps_state_prompted() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        prompter.recordPermissionAsked()
        prompter.recordInAppDismiss(PR_LIST_INCOMING_WITH_PRS)
        assertFalse(prompter.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
    }

    @Test
    fun recordPermissionAsked_after_recordInAppDismiss_keeps_state_prompted() {
        val prompter = NotificationPermissionPrompterImpl(InMemorySettings())
        prompter.recordInAppDismiss(PR_LIST_INCOMING_WITH_PRS)
        prompter.recordPermissionAsked()
        assertFalse(prompter.shouldPrompt(PR_DETAIL_OPENED))
    }

    @Test
    fun state_persists_across_instances_sharing_settings() {
        val settings = InMemorySettings()
        NotificationPermissionPrompterImpl(settings).recordInAppDismiss(PR_LIST_INCOMING_WITH_PRS)

        val secondInstance = NotificationPermissionPrompterImpl(settings)
        assertFalse(secondInstance.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
        assertFalse(secondInstance.shouldPrompt(PR_DETAIL_OPENED))
    }

    @Test
    fun unrelated_settings_keys_do_not_collide() {
        val settings = InMemorySettings()
        settings.putBoolean("some_other_key", true)
        val prompter = NotificationPermissionPrompterImpl(settings)
        assertTrue(prompter.shouldPrompt(PR_LIST_INCOMING_WITH_PRS))
    }

    @Test
    fun trigger_enum_has_three_entries_per_adr_017_section_3_6() {
        assertEquals(3, NotificationPromptTrigger.entries.size)
    }
}
