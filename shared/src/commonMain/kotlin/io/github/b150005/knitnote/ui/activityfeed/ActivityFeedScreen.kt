package io.github.b150005.knitnote.ui.activityfeed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import io.github.b150005.knitnote.domain.model.Activity
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_back
import io.github.b150005.knitnote.generated.resources.body_no_activity
import io.github.b150005.knitnote.generated.resources.label_activity_commented_by
import io.github.b150005.knitnote.generated.resources.label_activity_completed_by
import io.github.b150005.knitnote.generated.resources.label_activity_created_by
import io.github.b150005.knitnote.generated.resources.label_activity_forked_by
import io.github.b150005.knitnote.generated.resources.label_activity_shared_by
import io.github.b150005.knitnote.generated.resources.label_activity_started_by
import io.github.b150005.knitnote.generated.resources.label_someone
import io.github.b150005.knitnote.generated.resources.state_no_activity
import io.github.b150005.knitnote.generated.resources.title_activity_feed
import io.github.b150005.knitnote.ui.components.localized
import io.github.b150005.knitnote.ui.util.formatFull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityFeedScreen(
    onBack: () -> Unit,
    viewModel: ActivityFeedViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorText = state.error?.localized()

    LaunchedEffect(errorText) {
        errorText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(ActivityFeedEvent.ClearError)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.title_activity_feed)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("backButton")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("activityFeedScreen"),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.activities.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(Res.string.state_no_activity),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(Res.string.body_no_activity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.activities, key = { it.id }) { activity ->
                            ActivityListItem(
                                activity = activity,
                                user = state.users[activity.userId],
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityListItem(
    activity: Activity,
    user: User?,
) {
    val dateText = activity.createdAt.formatFull()
    val displayName = user?.displayName ?: stringResource(Res.string.label_someone)
    val (icon, templateRes) = activityTemplate(activity.type)
    val headline = stringResource(templateRes, displayName)

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = dateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun activityTemplate(type: ActivityType): Pair<ImageVector, StringResource> =
    when (type) {
        ActivityType.STARTED -> Icons.Default.Add to Res.string.label_activity_started_by
        ActivityType.COMPLETED -> Icons.Default.Check to Res.string.label_activity_completed_by
        ActivityType.SHARED -> Icons.Default.Share to Res.string.label_activity_shared_by
        ActivityType.COMMENTED -> Icons.AutoMirrored.Filled.Comment to Res.string.label_activity_commented_by
        ActivityType.FORKED -> Icons.AutoMirrored.Filled.CallSplit to Res.string.label_activity_forked_by
        ActivityType.CREATED -> Icons.Default.Add to Res.string.label_activity_created_by
    }
