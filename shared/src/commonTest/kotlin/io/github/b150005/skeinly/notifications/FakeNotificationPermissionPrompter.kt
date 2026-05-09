package io.github.b150005.skeinly.notifications

/**
 * In-memory fake for ViewModel + Composable tests landing in 24.2c
 * (pre-permission UI) and 24.2d (registrar wiring). Records the
 * sequence of calls so tests can assert on triggers + ordering
 * without standing up a Settings instance.
 */
class FakeNotificationPermissionPrompter : NotificationPermissionPrompter {
    val dismissedTriggers: MutableList<NotificationPromptTrigger> = mutableListOf()
    var permissionAskedCount: Int = 0
        private set

    /**
     * Backing flag. Defaults to true ("pristine user, would prompt").
     * Tests can flip it via [setPrompted] to simulate the persisted-prompted
     * state without firing the record* methods.
     */
    private var prompted: Boolean = false

    fun setPrompted(value: Boolean) {
        prompted = value
    }

    override fun shouldPrompt(trigger: NotificationPromptTrigger): Boolean = !prompted

    override fun recordInAppDismiss(trigger: NotificationPromptTrigger) {
        dismissedTriggers.add(trigger)
        prompted = true
    }

    override fun recordPermissionAsked() {
        permissionAskedCount += 1
        prompted = true
    }
}
