package io.github.b150005.knitnote.domain.repository

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

interface StorageOperations {
    suspend fun upload(
        userId: String,
        subFolder: String,
        fileName: String,
        data: ByteArray,
    ): String

    suspend fun createSignedUrl(
        path: String,
        expiresIn: Duration = 1.hours,
    ): String

    suspend fun createSignedUrls(
        paths: List<String>,
        expiresIn: Duration = 1.hours,
    ): List<String>

    suspend fun delete(paths: List<String>)
}
