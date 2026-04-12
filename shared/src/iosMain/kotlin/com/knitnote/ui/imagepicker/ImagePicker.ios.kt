@file:Suppress("ktlint:standard:filename")

package com.knitnote.ui.imagepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

@Composable
actual fun rememberImagePickerLauncher(onResult: (ImagePickerResult?) -> Unit): ImagePickerLauncher =
    remember(onResult) {
        ImagePickerLauncher(onResult)
    }

actual class ImagePickerLauncher(
    private val onResult: (ImagePickerResult?) -> Unit,
) {
    actual fun launch() {
        val configuration =
            PHPickerConfiguration().apply {
                selectionLimit = 1
                filter = PHPickerFilter.imagesFilter
            }

        val delegate = PickerDelegate(onResult)
        val picker = PHPickerViewController(configuration = configuration)
        picker.delegate = delegate

        val keyWindow =
            (UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene)
                ?.windows
                ?.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        keyWindow
            ?.rootViewController
            ?.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(
    private val onResult: (ImagePickerResult?) -> Unit,
) : NSObject(),
    PHPickerViewControllerDelegateProtocol {
    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)

        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            dispatch_async(dispatch_get_main_queue()) { onResult(null) }
            return
        }

        val provider = result.itemProvider
        if (!provider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {
            dispatch_async(dispatch_get_main_queue()) { onResult(null) }
            return
        }

        provider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, error ->
            if (error != null || data == null) {
                dispatch_async(dispatch_get_main_queue()) { onResult(null) }
                return@loadDataRepresentationForTypeIdentifier
            }

            val image = UIImage(data = data)
            val maxSize = com.knitnote.domain.usecase.UploadChartImageUseCase.MAX_IMAGE_SIZE
            val jpegData = compressToJpeg(image, maxSize)
            if (jpegData == null) {
                dispatch_async(dispatch_get_main_queue()) { onResult(null) }
                return@loadDataRepresentationForTypeIdentifier
            }

            val timestamp = NSDate().timeIntervalSince1970.toLong()
            val fileName = "chart_$timestamp.jpg"
            dispatch_async(dispatch_get_main_queue()) {
                onResult(
                    ImagePickerResult(
                        data = jpegData,
                        fileName = fileName,
                        mimeType = "image/jpeg",
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun compressToJpeg(
    image: UIImage,
    maxSize: Int,
): ByteArray? {
    var quality = 0.9
    while (quality >= 0.1) {
        val nsData = UIImageJPEGRepresentation(image, quality) ?: return null
        if (nsData.length.toInt() <= maxSize) {
            return nsData.toByteArray()
        }
        quality -= 0.1
    }
    val nsData = UIImageJPEGRepresentation(image, 0.1) ?: return null
    return nsData.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val byteArray = ByteArray(size)
    if (size > 0) {
        memcpy(byteArray.refTo(0), bytes, length)
    }
    return byteArray
}
