package com.knitnote.data.remote

import com.knitnote.domain.repository.StorageOperations
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration

class RemoteStorageDataSource(
    private val supabaseClient: SupabaseClient,
) : StorageOperations {
    private val bucket get() = supabaseClient.storage["chart-images"]

    override suspend fun upload(
        userId: String,
        patternId: String,
        fileName: String,
        data: ByteArray,
    ): String {
        val path = "$userId/$patternId/$fileName"
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
}
