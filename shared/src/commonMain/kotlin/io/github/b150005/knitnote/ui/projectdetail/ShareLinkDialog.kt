package io.github.b150005.knitnote.ui.projectdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.b150005.knitnote.generated.resources.Res
import io.github.b150005.knitnote.generated.resources.action_close
import io.github.b150005.knitnote.generated.resources.action_copy_link
import io.github.b150005.knitnote.generated.resources.dialog_share_link_body
import io.github.b150005.knitnote.generated.resources.dialog_share_link_title
import io.github.b150005.knitnote.generated.resources.label_share_url
import io.github.b150005.knitnote.generated.resources.message_link_copied
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShareLinkDialog(
    shareToken: String,
    onDismiss: () -> Unit,
) {
    // LocalClipboardManager is deprecated in favor of LocalClipboard + ClipEntry, but
    // ClipEntry's constructor is expect/actual (Android: ClipData, iOS: no-arg) — there is
    // no commonMain-compatible factory for plain-text entries in CMP 1.10.3.
    // Migrate when JetBrains adds a multiplatform ClipEntry text constructor.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val shareUrl = "knitnote://share/$shareToken"
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.dialog_share_link_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.dialog_share_link_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = shareUrl,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(Res.string.label_share_url)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(shareUrl))
                    copied = true
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (copied) Res.string.message_link_copied else Res.string.action_copy_link,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_close))
            }
        },
    )
}
