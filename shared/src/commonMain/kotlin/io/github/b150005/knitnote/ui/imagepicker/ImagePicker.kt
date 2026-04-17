@file:Suppress("ktlint:standard:filename")

package io.github.b150005.knitnote.ui.imagepicker

import androidx.compose.runtime.Composable

@Composable
expect fun rememberImagePickerLauncher(onResult: (ImagePickerResult?) -> Unit): ImagePickerLauncher

expect class ImagePickerLauncher {
    fun launch()
}
