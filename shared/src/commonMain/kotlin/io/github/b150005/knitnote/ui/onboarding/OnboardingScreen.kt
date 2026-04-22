package io.github.b150005.knitnote.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_get_started
import io.github.b150005.knitnote.generated.resources.action_next
import io.github.b150005.knitnote.generated.resources.action_skip
import io.github.b150005.knitnote.generated.resources.body_onboarding_count
import io.github.b150005.knitnote.generated.resources.body_onboarding_library
import io.github.b150005.knitnote.generated.resources.body_onboarding_track
import io.github.b150005.knitnote.generated.resources.title_onboarding_count
import io.github.b150005.knitnote.generated.resources.title_onboarding_library
import io.github.b150005.knitnote.generated.resources.title_onboarding_track
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Index-keyed copy. Order MUST match OnboardingViewModel.DEFAULT_PAGES.
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
    modifier: Modifier = Modifier,
) {
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
