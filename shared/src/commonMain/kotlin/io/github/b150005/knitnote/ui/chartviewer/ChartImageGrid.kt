package io.github.b150005.knitnote.ui.chartviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ChartImageGrid(
    signedUrls: List<String>,
    storagePaths: List<String>,
    isUploading: Boolean,
    onImageClick: (index: Int) -> Unit,
    onDeleteClick: (storagePath: String) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Chart Images",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val zippedImages = signedUrls.zip(storagePaths)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(zippedImages) { index, (url, path) ->
                ChartImageThumbnail(
                    imageUrl = url,
                    onClick = { onImageClick(index) },
                    onDelete = { onDeleteClick(path) },
                )
            }

            item {
                AddImageButton(
                    isUploading = isUploading,
                    onClick = onAddClick,
                )
            }
        }
    }
}

@Composable
private fun ChartImageThumbnail(
    imageUrl: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Chart thumbnail",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )

        // Delete button
        IconButton(
            onClick = onDelete,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .padding(2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onError,
                modifier =
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            RoundedCornerShape(12.dp),
                        ).padding(2.dp)
                        .size(16.dp),
            )
        }
    }
}

@Composable
private fun AddImageButton(
    isUploading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !isUploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add chart image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
