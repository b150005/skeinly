package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.repository.StorageOperations
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration

class RemoteStorageDataSource(
    private val supabaseClient: SupabaseClient,
    private val bucketName: String,
) : StorageOperations {
    private val bucket get() = supabaseClient.storage[bucketName]

    override suspend fun upload(
        userId: String,
        subFolder: String,
        fileName: String,
        data: ByteArray,
    ): String {
        val path = "$userId/$subFolder/$fileName"
        bucket.upload(path, data) {
            upsert = false
            contentType = ContentType.Image.JPEG
        }
        return path
    }

    override suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration,
    ): String = bucket.createSignedUrl(path, expiresIn)

    override suspend fun createSignedUrls(
        paths: List<String>,
        expiresIn: Duration,
    ): List<String> =
        if (paths.isEmpty()) {
            emptyList()
        } else {
            bucket.createSignedUrls(expiresIn, paths).map { it.signedURL }
        }

    override suspend fun delete(paths: List<String>) {
        if (paths.isNotEmpty()) {
            bucket.delete(paths)
        }
    }

    override fun publicUrl(path: String): String = bucket.publicUrl(path)
}
