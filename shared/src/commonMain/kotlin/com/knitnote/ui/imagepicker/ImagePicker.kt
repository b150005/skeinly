@file:Suppress("ktlint:standard:filename")

package com.knitnote.ui.imagepicker

import androidx.compose.runtime.Composable

@Composable
expect fun rememberImagePickerLauncher(onResult: (ImagePickerResult?) -> Unit): ImagePickerLauncher

expect class ImagePickerLauncher {
    fun launch()
}
