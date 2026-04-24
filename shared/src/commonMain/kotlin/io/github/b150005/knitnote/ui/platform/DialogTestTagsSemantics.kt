@file:Suppress("ktlint:standard:filename")

package io.github.b150005.knitnote.ui.platform

import androidx.compose.ui.Modifier

/**
 * Applies the Android `testTagsAsResourceId = true` semantics property inside
 * a Compose Dialog window so descendant `Modifier.testTag(...)` calls expose
 * as Android `resource-id` attributes to the accessibility tree. Required for
 * Maestro / UIAutomator `id:` selectors to resolve on AlertDialog content —
 * dialogs render in a separate Android Window and do not inherit the
 * `testTagsAsResourceId` semantics applied on the Activity root.
 *
 * iOS actual is a no-op: iOS SwiftUI surfaces use `.accessibilityIdentifier`
 * directly and have no equivalent cross-window semantics gap.
 */
expect fun Modifier.dialogTestTagsAsResourceId(): Modifier
