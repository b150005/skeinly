package io.github.b150005.knitnote.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable

/**
 * Sprint A PR5 — non-color selected indicator for `FilterChip`.
 *
 * Pass the resulting lambda to `FilterChip(leadingIcon = ...)`. When
 * `selected == true`, a checkmark renders alongside the chip label;
 * otherwise the chip stays compact with no leading slot consumed.
 *
 * Closes the WCAG 1.4.1 (Use of Color) finding from the pre-Phase-39
 * audit: the pale-lavender fill on selected chips was perceptually
 * indistinguishable from disabled for users with reduced color vision.
 *
 * Usage:
 * ```
 * FilterChip(
 *     selected = filter == Status.ALL,
 *     onClick = { ... },
 *     label = { Text("All") },
 *     leadingIcon = selectedCheckmarkIcon(filter == Status.ALL),
 * )
 * ```
 */
fun selectedCheckmarkIcon(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Default.Check, contentDescription = null) }
    } else {
        null
    }
