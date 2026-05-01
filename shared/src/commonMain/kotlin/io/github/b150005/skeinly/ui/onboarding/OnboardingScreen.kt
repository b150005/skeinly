package io.github.b150005.skeinly.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.skeinly.generated.resources.Res
import io.github.b150005.skeinly.generated.resources.action_get_started
import io.github.b150005.skeinly.generated.resources.action_next
import io.github.b150005.skeinly.generated.resources.action_skip
import io.github.b150005.skeinly.generated.resources.body_diagnostic_consent
import io.github.b150005.skeinly.generated.resources.body_diagnostic_data_explanation
import io.github.b150005.skeinly.generated.resources.body_onboarding_count
import io.github.b150005.skeinly.generated.resources.body_onboarding_library
import io.github.b150005.skeinly.generated.resources.body_onboarding_track
import io.github.b150005.skeinly.generated.resources.label_diagnostic_data_sharing
import io.github.b150005.skeinly.generated.resources.title_diagnostic_consent
import io.github.b150005.skeinly.generated.resources.title_onboarding_count
import io.github.b150005.skeinly.generated.resources.title_onboarding_library
import io.github.b150005.skeinly.generated.resources.title_onboarding_track
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Index-keyed copy. Order MUST match OnboardingViewModel.DEFAULT_PAGES.
// Note that the Phase 39.4 diagnostic-consent page (iconName ==
// "diagnostic_data") is rendered by its own branch in
// [OnboardingPageContent] and does NOT consume entries from these
// arrays — the consent page has a Toggle + dedicated copy keyed by
// `title_diagnostic_consent` / `body_diagnostic_consent`.
private val onboardingTitleKeys: List<StringResource> =
    listOf(
        Res.string.title_onboarding_track,
        Res.string.title_onboarding_count,
        Res.string.title_onboarding_library,
    )

private val onboardingBodyKeys: List<StringResource> =
    listOf(
        Res.string.body_onboarding_track,
        Res.string.body_onboarding_count,
        Res.string.body_onboarding_library,
    )

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { state.pages.size })
    val scope = rememberCoroutineScope()

    // Sync pager state → ViewModel when user swipes
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.onEvent(OnboardingEvent.PageChanged(page))
        }
    }

    // Navigate away when onboarding is completed
    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) {
            onComplete()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
    ) {
        // Skip button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { viewModel.onEvent(OnboardingEvent.Skip) },
                modifier = Modifier.testTag("skipButton"),
            ) {
                Text(stringResource(Res.string.action_skip))
            }
        }

        // Pager
        HorizontalPager(
            state = pagerState,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("onboardingPager"),
        ) { pageIndex ->
            OnboardingPageContent(
                page = state.pages[pageIndex],
                pageIndex = pageIndex,
                analyticsOptIn = state.analyticsOptIn,
                onAnalyticsOptInChanged = { value ->
                    viewModel.onEvent(OnboardingEvent.SetAnalyticsOptIn(value))
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("onboardingPage${pageIndex + 1}"),
            )
        }

        // Page indicator dots
        PageIndicator(
            pageCount = state.pages.size,
            currentPage = pagerState.currentPage,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
        )

        // Action button — use pagerState as authoritative source to avoid one-frame lag
        val isLastPage = pagerState.currentPage == state.pages.lastIndex
        FilledTonalButton(
            onClick = {
                if (isLastPage) {
                    viewModel.onEvent(OnboardingEvent.Complete)
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(if (isLastPage) "getStartedButton" else "nextButton"),
        ) {
            Text(
                stringResource(
                    if (isLastPage) Res.string.action_get_started else Res.string.action_next,
                ),
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    pageIndex: Int,
    analyticsOptIn: Boolean,
    onAnalyticsOptInChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Phase 39.4: the diagnostic-consent page is identified by its
    // sentinel iconName, NOT by index — adding a 5th page later does not
    // silently break the consent toggle if it is appended after the
    // diagnostic page (or vice-versa).
    if (page.iconName == "diagnostic_data") {
        DiagnosticConsentPageContent(
            analyticsOptIn = analyticsOptIn,
            onAnalyticsOptInChanged = onAnalyticsOptInChanged,
            modifier = modifier,
        )
        return
    }

    // Fallback to the first entry if a new page is added to DEFAULT_PAGES
    // without a matching key entry. This keeps the carousel renderable instead
    // of crashing at index-out-of-bounds, but the build should never ship in
    // that state — adding a page requires updating onboardingTitleKeys +
    // onboardingBodyKeys + i18n resources in the same change.
    val titleKey = onboardingTitleKeys.getOrElse(pageIndex) { onboardingTitleKeys.first() }
    val bodyKey = onboardingBodyKeys.getOrElse(pageIndex) { onboardingBodyKeys.first() }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = mapIconName(page.iconName),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(titleKey),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(bodyKey),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticConsentPageContent(
    analyticsOptIn: Boolean,
    onAnalyticsOptInChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Insights,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(Res.string.title_diagnostic_consent),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.body_diagnostic_consent),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.label_diagnostic_data_sharing))
            },
            trailingContent = {
                // `onCheckedChange = null` so the row's `clickable` is
                // the single dispatch site — same Switch + clickable
                // pattern as SettingsScreen.kt's analytics toggle, which
                // avoids the double-fire bug if both fired together.
                Switch(
                    checked = analyticsOptIn,
                    onCheckedChange = null,
                    modifier = Modifier.testTag("diagnosticConsentSwitch"),
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("diagnosticConsentRow")
                    .clickable(role = Role.Switch) {
                        onAnalyticsOptInChanged(!analyticsOptIn)
                    },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.body_diagnostic_data_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pageCount) { index ->
            val color by animateColorAsState(
                targetValue =
                    if (index == currentPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            )
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color),
            )
        }
    }
}

private fun mapIconName(name: String): ImageVector =
    when (name) {
        "home" -> Icons.Default.Home
        "add_circle" -> Icons.Default.AddCircle
        "favorite" -> Icons.Default.Favorite
        else -> Icons.Default.Home
    }
