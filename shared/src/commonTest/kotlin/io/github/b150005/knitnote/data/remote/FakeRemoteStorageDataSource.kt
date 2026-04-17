package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.repository.StorageOperations
import kotlin.time.Duration

class FakeRemoteStorageDataSource : StorageOperations {
    private val uploadedFiles = mutableMapOf<String, ByteArray>()
    var uploadError: Exception? = null
    var deleteError: Exception? = null

    override suspend fun upload(
        userId: String,
        subFolder: String,
        fileName: String,
        data: ByteArray,
    ): String {
        uploadError?.let { throw it }
        val path = "$userId/$subFolder/$fileName"
        uploadedFiles[path] = data
        return path
    }

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
    ): String = "https://storage.example.com/signed/$path"

    override suspend fun createSignedUrls(
        paths: List<String>,
        expiresIn: Duration,
    ): List<String> = paths.map { "https://storage.example.com/signed/$it" }

    override suspend fun delete(paths: List<String>) {
        deleteError?.let { throw it }
        paths.forEach { uploadedFiles.remove(it) }
    }

    fun getUploadedFile(path: String): ByteArray? = uploadedFiles[path]

    fun getUploadedFileCount(): Int = uploadedFiles.size
}
