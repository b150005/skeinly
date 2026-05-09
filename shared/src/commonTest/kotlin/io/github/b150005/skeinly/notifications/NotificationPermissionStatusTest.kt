package io.github.b150005.skeinly.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 24.2b — closed-enum invariants for the push permission status.
 *
 * The enum order is load-bearing: a future migration that adds a 4th
 * status (e.g. `PROVISIONAL` for iOS .provisional or `EPHEMERAL`) MUST
 * preserve the ordering of the existing 3 entries so any persisted
 * ordinal-int shape stays valid. Today nothing serializes the ordinal
 * (the value lives in OS state), but the ordinal-stability rule is
 * cheap to assert and saves a future maintainer the slow debug.
 */
class NotificationPermissionStatusTest {
    @Test
    fun status_enum_has_three_entries_per_adr_017_section_3_6() {
        assertEquals(3, NotificationPermissionStatus.entries.size)
    }

    @Test
    fun status_enum_carries_not_determined_first_for_ordinal_stability() {
        assertEquals(NotificationPermissionStatus.NOT_DETERMINED, NotificationPermissionStatus.entries[0])
    }

    @Test
    fun status_enum_carries_granted_and_denied_as_terminal_states() {
        val terminal = NotificationPermissionStatus.entries.filterNot { it == NotificationPermissionStatus.NOT_DETERMINED }
        assertTrue(NotificationPermissionStatus.GRANTED in terminal)
        assertTrue(NotificationPermissionStatus.DENIED in terminal)
    }
}
