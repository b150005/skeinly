package io.github.b150005.knitnote.ui.projectdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_cancel
import io.github.b150005.knitnote.generated.resources.action_share
import io.github.b150005.knitnote.generated.resources.dialog_share_with_user_title
import io.github.b150005.knitnote.generated.resources.label_no_name
import io.github.b150005.knitnote.generated.resources.label_permission
import io.github.b150005.knitnote.generated.resources.label_permission_fork
import io.github.b150005.knitnote.generated.resources.label_permission_view
import io.github.b150005.knitnote.generated.resources.label_search_by_name
import io.github.b150005.knitnote.generated.resources.label_selected_prefix
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun UserPickerDialog(
    onDismiss: () -> Unit,
    onUserSelected: (userId: String, permission: SharePermission) -> Unit,
) {
    val userRepository: UserRepository = koinInject()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedUser by remember { mutableStateOf<User?>(null) }
    var selectedPermission by remember { mutableStateOf(SharePermission.VIEW) }

    val noNameLabel = stringResource(Res.string.label_no_name)

    // Debounced search
    LaunchedEffect(query) {
        if (query.length < 2) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searchResults = userRepository.searchByDisplayName(query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_share_with_user_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (selectedUser != null) selectedUser = null
                    },
                    label = { Text(stringResource(Res.string.label_search_by_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                selectedUser?.let { user ->
                    Text(
                        text =
                            stringResource(
                                Res.string.label_selected_prefix,
                                user.displayName,
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (selectedUser == null && searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                    ) {
                        items(searchResults, key = { it.id }) { user ->
                            ListItem(
                                modifier =
                                    Modifier.clickable {
                                        selectedUser = user
                                        query = user.displayName
                                    },
                                headlineContent = {
                                    Text(user.displayName.ifBlank { noNameLabel })
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.label_permission),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedPermission == SharePermission.VIEW,
                        onClick = { selectedPermission = SharePermission.VIEW },
                        label = { Text(stringResource(Res.string.label_permission_view)) },
                    )
                    FilterChip(
                        selected = selectedPermission == SharePermission.FORK,
                        onClick = { selectedPermission = SharePermission.FORK },
                        label = { Text(stringResource(Res.string.label_permission_fork)) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedUser?.let { user ->
                        onUserSelected(user.id, selectedPermission)
                    }
                },
                enabled = selectedUser != null,
            ) {
                Text(stringResource(Res.string.action_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
