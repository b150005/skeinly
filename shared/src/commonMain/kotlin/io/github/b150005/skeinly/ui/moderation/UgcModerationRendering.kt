package io.github.b150005.skeinly.ui.moderation

import androidx.compose.runtime.Composable
import io.github.b150005.skeinly.domain.model.UgcReportCategory
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.report_category_harassment
import io.github.b150005.skeinly.generated.resources.report_category_hate
import io.github.b150005.skeinly.generated.resources.report_category_ip
import io.github.b150005.skeinly.generated.resources.report_category_other
import io.github.b150005.skeinly.generated.resources.report_category_sexual
import io.github.b150005.skeinly.generated.resources.report_category_spam
import io.github.b150005.skeinly.generated.resources.report_category_violence
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 39 (ADR-021 §D4) — Compose-side resolver: a [UgcReportCategory]
 * → its localized picker label.
 *
 * The `when` is exhaustive by enum enforcement — adding a category to
 * [UgcReportCategory] (which must move with the migration-031 CHECK +
 * the Edge Function's `REASON_CATEGORIES`) is a compile-time error
 * here until a label key is wired.
 *
 * Mirror: iOS `UgcReportCategory+Localized.swift` `localizedLabel`.
 */
@Composable
fun UgcReportCategory.localizedLabel(): String =
    when (this) {
        UgcReportCategory.Spam -> stringResource(Res.string.report_category_spam)
        UgcReportCategory.Harassment -> stringResource(Res.string.report_category_harassment)
        UgcReportCategory.Sexual -> stringResource(Res.string.report_category_sexual)
        UgcReportCategory.Violence -> stringResource(Res.string.report_category_violence)
        UgcReportCategory.Hate -> stringResource(Res.string.report_category_hate)
        UgcReportCategory.IntellectualProperty -> stringResource(Res.string.report_category_ip)
        UgcReportCategory.Other -> stringResource(Res.string.report_category_other)
    }
