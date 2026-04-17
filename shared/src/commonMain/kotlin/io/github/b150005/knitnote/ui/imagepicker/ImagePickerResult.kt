package io.github.b150005.knitnote.ui.imagepicker

data class ImagePickerResult(
    val data: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImagePickerResult) return false
        return data.contentEquals(other.data) && fileName == other.fileName && mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
