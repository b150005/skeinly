@file:Suppress("ktlint:standard:filename")

package com.knitnote.ui.imagepicker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.knitnote.domain.usecase.UploadChartImageUseCase
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberImagePickerLauncher(onResult: (ImagePickerResult?) -> Unit): ImagePickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri == null) {
                onResult(null)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        val inputStream =
                            context.contentResolver.openInputStream(uri)
                                ?: return@withContext null
                        val originalBytes = inputStream.use { it.readBytes() }

                        val compressed =
                            compressToJpeg(originalBytes, UploadChartImageUseCase.MAX_IMAGE_SIZE)
                                ?: return@withContext null

                        val fileName = "chart_${System.currentTimeMillis()}.jpg"
                        ImagePickerResult(
                            data = compressed,
                            fileName = fileName,
                            mimeType = "image/jpeg",
                        )
                    }
                onResult(result)
            }
        }

    return remember(launcher) {
        ImagePickerLauncher(launcher)
    }
}

actual class ImagePickerLauncher(
    private val launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
) {
    actual fun launch() {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
}

private fun compressToJpeg(
    originalBytes: ByteArray,
    maxSize: Int,
): ByteArray? {
    val bitmap =
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            ?: return null
    try {
        var quality = 90
        while (quality >= 10) {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val compressed = outputStream.toByteArray()
            if (compressed.size <= maxSize) return compressed
            quality -= 10
        }
        // Last resort: return lowest quality (UseCase validates size)
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 10, outputStream)
        return outputStream.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
