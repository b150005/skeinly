@file:Suppress("ktlint:standard:filename")

package io.github.b150005.skeinly.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

actual fun Modifier.dialogTestTagsAsResourceId(): Modifier = this.semantics { testTagsAsResourceId = true }
