package io.github.b150005.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.b150005.knitnote.data.mapper.toChartImageUrlsDbString
import io.github.b150005.knitnote.data.mapper.toDbString
import io.github.b150005.knitnote.data.mapper.toDomain
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.domain.model.Pattern
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalPatternDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.patternQueries

    suspend fun getById(id: String): Pattern? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByOwnerId(ownerId: String): List<Pattern> =
        withContext(ioDispatcher) {
            queries.getByOwnerId(ownerId).executeAsList().map { it.toDomain() }
        }

    fun observeById(id: String): Flow<Pattern?> =
        queries
            .observeById(id)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { it?.toDomain() }

    fun observeByOwnerId(ownerId: String): Flow<List<Pattern>> =
        queries
            .getByOwnerId(ownerId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { list -> list.map { it.toDomain() } }

    suspend fun insert(pattern: Pattern): Pattern =
        withContext(ioDispatcher) {
            queries.insert(
                id = pattern.id,
                owner_id = pattern.ownerId,
                title = pattern.title,
                description = pattern.description,
                difficulty = pattern.difficulty?.toDbString(),
                gauge = pattern.gauge,
                yarn_info = pattern.yarnInfo,
                needle_size = pattern.needleSize,
                chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                visibility = pattern.visibility.toDbString(),
                created_at = pattern.createdAt.toString(),
                updated_at = pattern.updatedAt.toString(),
            )
            pattern
        }

    suspend fun update(pattern: Pattern): Pattern =
        withContext(ioDispatcher) {
            queries.update(
                title = pattern.title,
                description = pattern.description,
                difficulty = pattern.difficulty?.toDbString(),
                gauge = pattern.gauge,
                yarn_info = pattern.yarnInfo,
                needle_size = pattern.needleSize,
                chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                visibility = pattern.visibility.toDbString(),
                updated_at = pattern.updatedAt.toString(),
                id = pattern.id,
            )
            pattern
        }

    suspend fun delete(id: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }

    suspend fun upsert(pattern: Pattern): Unit =
        withContext(ioDispatcher) {
            db.transaction {
                val exists = queries.getById(pattern.id).executeAsOneOrNull() != null
                if (exists) {
                    queries.update(
                        title = pattern.title,
                        description = pattern.description,
                        difficulty = pattern.difficulty?.toDbString(),
                        gauge = pattern.gauge,
                        yarn_info = pattern.yarnInfo,
                        needle_size = pattern.needleSize,
                        chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                        visibility = pattern.visibility.toDbString(),
                        updated_at = pattern.updatedAt.toString(),
                        id = pattern.id,
                    )
                } else {
                    queries.insert(
                        id = pattern.id,
                        owner_id = pattern.ownerId,
                        title = pattern.title,
                        description = pattern.description,
                        difficulty = pattern.difficulty?.toDbString(),
                        gauge = pattern.gauge,
                        yarn_info = pattern.yarnInfo,
                        needle_size = pattern.needleSize,
                        chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                        visibility = pattern.visibility.toDbString(),
                        created_at = pattern.createdAt.toString(),
                        updated_at = pattern.updatedAt.toString(),
                    )
                }
            }
        }

    suspend fun upsertAll(patterns: List<Pattern>) =
        withContext(ioDispatcher) {
            db.transaction {
                patterns.forEach { pattern ->
                    val exists = queries.getById(pattern.id).executeAsOneOrNull() != null
                    if (exists) {
                        queries.update(
                            title = pattern.title,
                            description = pattern.description,
                            difficulty = pattern.difficulty?.toDbString(),
                            gauge = pattern.gauge,
                            yarn_info = pattern.yarnInfo,
                            needle_size = pattern.needleSize,
                            chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                            visibility = pattern.visibility.toDbString(),
                            updated_at = pattern.updatedAt.toString(),
                            id = pattern.id,
                        )
                    } else {
                        queries.insert(
                            id = pattern.id,
                            owner_id = pattern.ownerId,
                            title = pattern.title,
                            description = pattern.description,
                            difficulty = pattern.difficulty?.toDbString(),
                            gauge = pattern.gauge,
                            yarn_info = pattern.yarnInfo,
                            needle_size = pattern.needleSize,
                            chart_image_urls = pattern.chartImageUrls.toChartImageUrlsDbString(),
                            visibility = pattern.visibility.toDbString(),
                            created_at = pattern.createdAt.toString(),
                            updated_at = pattern.updatedAt.toString(),
                        )
                    }
                }
            }
        }
}
