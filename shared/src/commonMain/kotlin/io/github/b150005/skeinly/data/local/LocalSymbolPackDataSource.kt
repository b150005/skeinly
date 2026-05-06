package io.github.b150005.skeinly.data.local

import io.github.b150005.skeinly.data.mapper.toDbString
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.DownloadedPackPayloadEntity
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.SymbolPack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Phase 41.2b (ADR-016 §3.1, §4.3) — local mirror of `public.symbol_packs`
 * (catalog metadata) plus the downloaded `payload.json` body cache.
 *
 * Two surfaces, deliberately on one class:
 *  - **Catalog metadata** ([upsertPack] / [getAllPacks] / [getPackById]):
 *    every pack the manifest fetch surfaces, regardless of download status.
 *    Free + Pro packs both land here so the paywall preview UI can list
 *    Pro packs to non-subscribers without forcing a payload fetch.
 *  - **Downloaded payload** ([upsertPayload] / [getLatestPayload] / etc.):
 *    only packs the user has materialized locally. CompositeSymbolCatalog
 *    (Phase 41.2c) reads from here on the symbol-resolution hot path.
 *
 * **Why one class, two tables.** A separate `LocalDownloadedPackStore` would
 * have two consumers (sync manager + composite catalog) reach independently
 * into the database, and the sync's "delete old version after committing
 * new" path needs both tables under coordinated writes. Single class keeps
 * the transaction boundary visible.
 *
 * **Refresh semantics.** [replaceManifest] drops every metadata row that's
 * NOT in the supplied set (server-side archived packs disappear from local
 * cache too). It does NOT touch the payload table — a user who downloaded
 * a pack that has since been archived keeps the payload locally until
 * either logout or manual "free up storage". This is deliberate: a pack
 * archive on the server should not silently delete a chart cell's symbol
 * the user authored against the archived pack.
 */
class LocalSymbolPackDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
) {
    private val packQueries get() = db.symbolPackQueries
    private val payloadQueries get() = db.downloadedPackPayloadQueries

    // ----- Catalog metadata --------------------------------------------------

    suspend fun getAllPacks(): List<SymbolPack> =
        withContext(ioDispatcher) {
            packQueries.getAll().executeAsList().map { it.toDomain() }
        }

    suspend fun getPackById(packId: String): SymbolPack? =
        withContext(ioDispatcher) {
            packQueries.getById(packId).executeAsOneOrNull()?.toDomain()
        }

    suspend fun upsertPack(pack: SymbolPack) {
        withContext(ioDispatcher) {
            packQueries.upsert(
                id = pack.id,
                tier = pack.tier.toDbString(),
                version = pack.version.toLong(),
                display_name = pack.displayName,
                description = pack.description,
                payload_path = pack.payloadPath,
                payload_size = pack.payloadSize.toLong(),
                symbol_count = pack.symbolCount.toLong(),
                signed_until = pack.signedUntil?.toString(),
                created_at = pack.createdAt.toString(),
                updated_at = pack.updatedAt.toString(),
            )
        }
    }

    /**
     * Replaces the catalog mirror with the supplied manifest set in a single
     * transaction:
     *   - upsert every supplied pack (newer fields overwrite the cached row),
     *   - delete every cached pack id that's not in the supplied set.
     *
     * Payload table is intentionally NOT cascaded — see class KDoc for why.
     */
    suspend fun replaceManifest(packs: List<SymbolPack>) {
        withContext(ioDispatcher) {
            db.transaction {
                val supplied = packs.map { it.id }.toSet()
                val cached = packQueries.getAll().executeAsList().map { it.id }
                cached.forEach { id ->
                    if (id !in supplied) packQueries.deleteById(id)
                }
                packs.forEach { pack ->
                    packQueries.upsert(
                        id = pack.id,
                        tier = pack.tier.toDbString(),
                        version = pack.version.toLong(),
                        display_name = pack.displayName,
                        description = pack.description,
                        payload_path = pack.payloadPath,
                        payload_size = pack.payloadSize.toLong(),
                        symbol_count = pack.symbolCount.toLong(),
                        signed_until = pack.signedUntil?.toString(),
                        created_at = pack.createdAt.toString(),
                        updated_at = pack.updatedAt.toString(),
                    )
                }
            }
        }
    }

    suspend fun clearAllPacks() {
        withContext(ioDispatcher) {
            packQueries.clearAll()
        }
    }

    // ----- Downloaded payload ------------------------------------------------

    /**
     * Latest version of the payload locally available for [packId], or null
     * if the pack is not downloaded. CompositeSymbolCatalog hot path.
     */
    suspend fun getLatestPayload(packId: String): DownloadedPackPayload? =
        withContext(ioDispatcher) {
            payloadQueries.getLatestByPackId(packId).executeAsOneOrNull()?.toDownloadedPayload()
        }

    suspend fun getAllPayloads(): List<DownloadedPackPayload> =
        withContext(ioDispatcher) {
            payloadQueries.getAll().executeAsList().map { it.toDownloadedPayload() }
        }

    suspend fun hasPayloadForVersion(
        packId: String,
        version: Int,
    ): Boolean =
        withContext(ioDispatcher) {
            payloadQueries.existsForVersion(packId, version.toLong()).executeAsOne() > 0
        }

    /**
     * Atomic version-bump commit: writes the new payload row, then deletes
     * any strictly-older row for the same pack inside the same SQLDelight
     * transaction. The new-then-delete-older ordering keeps the catalog
     * observable to readers at every step — `getLatestPayload`'s
     * `ORDER BY version DESC LIMIT 1` already picks the right row during
     * any transient overlap.
     *
     * The targeted SQL DELETE keeps the transaction tight; an earlier
     * implementation loaded every pack's payload into memory just to
     * filter — needless cost as pack count grows.
     */
    suspend fun upsertPayload(
        packId: String,
        version: Int,
        payloadJson: String,
    ) {
        val downloadedAt = clock.now()
        withContext(ioDispatcher) {
            db.transaction {
                payloadQueries.upsert(
                    pack_id = packId,
                    version = version.toLong(),
                    payload_json = payloadJson,
                    downloaded_at = downloadedAt.toString(),
                )
                payloadQueries.deleteOlderVersionsForPack(packId, version.toLong())
            }
        }
    }

    suspend fun deletePayloadsForPack(packId: String) {
        withContext(ioDispatcher) {
            payloadQueries.deleteAllForPack(packId)
        }
    }

    suspend fun clearAllPayloads() {
        withContext(ioDispatcher) {
            payloadQueries.clearAll()
        }
    }

    /**
     * Wholesale logout cleanup. Drops both metadata + payload tables; the
     * next signed-in user starts with an empty mirror that the next sync
     * fills.
     */
    suspend fun clearAll() {
        withContext(ioDispatcher) {
            db.transaction {
                packQueries.clearAll()
                payloadQueries.clearAll()
            }
        }
    }
}

/**
 * Lightweight value type returned to consumers — exposes downloaded_at as a
 * proper [Instant] without leaking the SQLDelight-generated type. Phase 41.2c
 * will read [payloadJson] through `Json.decodeFromString<SymbolPackPayload>`
 * to materialize the catalog's symbol entries.
 */
data class DownloadedPackPayload(
    val packId: String,
    val version: Int,
    val payloadJson: String,
    val downloadedAt: Instant,
)

internal fun DownloadedPackPayloadEntity.toDownloadedPayload(): DownloadedPackPayload =
    DownloadedPackPayload(
        packId = pack_id,
        version = version.toInt(),
        payloadJson = payload_json,
        downloadedAt = Instant.parse(downloaded_at),
    )
