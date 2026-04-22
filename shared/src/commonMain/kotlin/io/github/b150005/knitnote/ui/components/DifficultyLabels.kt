package io.github.b150005.knitnote.ui.components

import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.label_difficulty_advanced
import io.github.b150005.knitnote.generated.resources.label_difficulty_beginner
import io.github.b150005.knitnote.generated.resources.label_difficulty_intermediate
import org.jetbrains.compose.resources.StringResource

// Shared mapping from Difficulty to its user-visible i18n key. Consumed by
// DiscoveryScreen's filter chips + badge and PatternLibraryScreen's filter
// chips + badge. When adding a new Difficulty case, add a matching
// `label_difficulty_<name>` key to all 5 i18n sources per
// docs/en/i18n-convention.md.
val Difficulty.labelKey: StringResource
    get() =
        when (this) {
            Difficulty.BEGINNER -> Res.string.label_difficulty_beginner
            Difficulty.INTERMEDIATE -> Res.string.label_difficulty_intermediate
            Difficulty.ADVANCED -> Res.string.label_difficulty_advanced
        }
