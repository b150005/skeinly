package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.data.local.DownloadedPackPayload
import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.mapper.toDefinition
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Phase 41.2c (ADR-016 §4.1) — composite [SymbolCatalog] that overlays
 * downloaded symbol packs on top of the bundled compile-time
 * [io.github.b150005.skeinly.domain.symbol.catalog.DefaultSymbolCatalog],
 * gated by [EntitlementResolver] for Pro-tier entries.
 *
 * **Lookup order** (per ADR-016 §4.1):
 *  1. Downloaded packs first — newer-version-wins across packs that contain
 *     the same symbol id. If the resolved entry's parent pack is `tier='pro'`
 *     and [EntitlementResolver.isPro] returns false, [get] returns null so
 *     the renderer falls back to its "?" glyph.
 *  2. Bundled compile-time catalog as the offline fallback. First-launch
 *     users with no network see exactly what they see today.
 *
 * **Why a memory snapshot instead of decode-on-every-`get`.** [SymbolCatalog]
 * is consumed on the Compose / SwiftUI render hot path (palette + cell draw).
 * `get(id)` is synchronous by interface contract. JSON-decoding the
 * `payload.json` body on every call would dwarf the actual rendering cost
 * (decode is ms-scale; cell render is µs-scale). Per the agent-team decision
 * recorded in this Phase 41.2c commit, we cache a [DownloadedSnapshot] in a
 * [MutableStateFlow] and rebuild it under [refresh].
 *
 * **Lifecycle.** The constructor schedules a single warm-up [refresh] on
 * [applicationScope] so the snapshot lands shortly after process boot. Until
 * the warm-up completes the snapshot is empty and every read falls through
 * to [bundled] — exactly the "sync still in flight" UX named in ADR-016 §8 #3.
 *
 * **No external sync trigger in 41.2c.** Phase 41.3 will wire the
 * post-purchase RevenueCat callback + foreground-resume hook to invoke
 * [refresh] after [io.github.b150005.skeinly.data.sync.SymbolPackSyncManager]
 * lands new payloads. Until then [refresh] runs only at process boot.
 *
 * **Per-pack-version policy.** Within a single pack, [refresh] consumes the
 * newest cached version via
 * [LocalSymbolPackDataSource.getLatestPayload]. Across packs that
 * coincidentally publish the same symbol id, packs are iterated
 * oldest-`updatedAt`-first so a newer pack's entry overwrites an older one in
 * the accumulation map — last-write-wins where "last" means newest-`updatedAt`.
 * Ties broken deterministically on pack `id` (see `buildSnapshot`).
 *
 * **Broken payloads degrade gracefully.** A payload that fails to decode (a
 * server-side packaging bug, a partial download persisted to disk before
 * Phase 41.2b's transactional upsert closes the gap, or a future
 * `SymbolPackPayload.schemaVersion` bump older clients can't parse) is
 * logged-and-skipped at refresh time. The bundled catalog still renders for
 * its symbols and unrelated packs continue to render normally — partial
 * decode failure must not blank the entire palette.
 */
class CompositeSymbolCatalog(
    private val bundled: SymbolCatalog,
    private val localSymbolPackDataSource: LocalSymbolPackDataSource,
    private val entitlementResolver: EntitlementResolver,
    private val json: Json,
    applicationScope: CoroutineScope,
) : SymbolCatalog {
    private val snapshotState = MutableStateFlow(DownloadedSnapshot.EMPTY)
    private val refreshMutex = Mutex()

    /**
     * Observable snapshot — Phase 41.2c has no observer, but exposing the
     * Flow keeps Phase 41.4's pack-management screen wiring trivial (it can
     * `collect { repaint }` after the user toggles a pack).
     */
    val snapshot: StateFlow<DownloadedSnapshot> = snapshotState.asStateFlow()

    init {
        // Fire-and-forget warm-up. Wrap in try/catch so a transient SQLDelight
        // failure or a parse error during the first build doesn't escape onto
        // the supervisor scope's uncaught handler with no observability path.
        // Phase 41.4 may surface this through an observable error state for
        // the pack-management screen; until then, log so the failure is at
        // least visible in the platform stderr / crash report stream.
        applicationScope.launch {
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // TODO(Phase 41.4): expose via refreshError StateFlow so the
                // pack-management UI can surface "catalog unavailable".
                println("CompositeSymbolCatalog warm-up refresh failed: $e")
            }
        }
    }

    override fun get(id: String): SymbolDefinition? {
        val current = snapshotState.value
        val entry = current.byId[id] ?: return bundled.get(id)
        // Snapshot the entitlement once per call so the within-call view stays
        // coherent — `listByCategory` and `all` follow the same shape.
        val isUserPro = entitlementResolver.isPro()
        if (entry.tier == SymbolPackTier.PRO && !isUserPro) {
            return null
        }
        return entry.definition
    }

    override fun listByCategory(category: SymbolCategory): List<SymbolDefinition> {
        val current = snapshotState.value
        val isUserPro = entitlementResolver.isPro()
        // Bundled first; downloaded entries with the same id then override.
        // Note: when a Pro downloaded entry shadows a bundled FREE entry of the
        // same id and the user is not Pro, the bundled entry stays. This is
        // deliberately divergent from `get()`'s null return — `listByCategory`
        // surfaces what the user CAN see, while `get()` is render-time lookup
        // for a specific id where falling back to the bundled free version
        // would silently substitute a different (free) symbol for the
        // requested (Pro) one. The test
        // `listByCategory keeps bundled free entry when pro pack supplies same id`
        // pins this divergence explicitly.
        val merged = LinkedHashMap<String, SymbolDefinition>()
        for (def in bundled.listByCategory(category)) {
            merged[def.id] = def
        }
        for (entry in current.byCategory[category].orEmpty()) {
            if (entry.tier == SymbolPackTier.PRO && !isUserPro) continue
            merged[entry.definition.id] = entry.definition
        }
        return merged.values.sortedBy { it.id }
    }

    override fun all(): List<SymbolDefinition> {
        val current = snapshotState.value
        val isUserPro = entitlementResolver.isPro()
        val merged = LinkedHashMap<String, SymbolDefinition>()
        for (def in bundled.all()) {
            merged[def.id] = def
        }
        for (entry in current.allEntries) {
            if (entry.tier == SymbolPackTier.PRO && !isUserPro) continue
            merged[entry.definition.id] = entry.definition
        }
        // Match DefaultSymbolCatalog ordering: category then id.
        val byCategory = merged.values.groupBy { it.category }
        return SymbolCategory.entries.flatMap { cat ->
            byCategory[cat].orEmpty().sortedBy { it.id }
        }
    }

    /**
     * Rebuilds the in-memory snapshot from the local mirror. Idempotent and
     * mutex-serialized — a second concurrent caller waits rather than
     * trampling the in-flight rebuild. Suspending; meant to be called from
     * Phase 41.3's foreground-hook + post-purchase callbacks (and from this
     * class's [init] warm-up).
     */
    suspend fun refresh() {
        refreshMutex.withLock {
            snapshotState.value = buildSnapshot()
        }
    }

    private suspend fun buildSnapshot(): DownloadedSnapshot {
        val packs = localSymbolPackDataSource.getAllPacks()
        if (packs.isEmpty()) return DownloadedSnapshot.EMPTY

        // Tier lookup keyed by pack id — needed when we resolve a payload entry
        // back to its parent pack's tier. The catalog metadata table is the
        // single source of truth for tier (pack-level), even though every
        // payload entry carries its own per-symbol tier (forward-compat for a
        // future "free pack with some paid symbols" arrangement, ADR-016 §3.4).
        val packsById = packs.associateBy { it.id }

        // Cross-pack id collision policy: newer pack.updatedAt wins. Iterate
        // packs oldest-first so the newer entry overwrites in the LinkedHashMap.
        // Tie-break on pack.id when two packs share an updatedAt timestamp
        // (plausible if the server batch-updates metadata in one transaction)
        // so the winner is fully deterministic across cold/warm SQLite scans.
        val orderedPacks = packs.sortedWith(compareBy({ it.updatedAt }, { it.id }))

        val byId = LinkedHashMap<String, DownloadedEntry>()
        for (pack in orderedPacks) {
            val payload = localSymbolPackDataSource.getLatestPayload(pack.id) ?: continue
            val decoded = decodePayload(pack.id, payload) ?: continue
            for (entry in decoded.symbols) {
                val definition =
                    try {
                        entry.toDefinition()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IllegalArgumentException) {
                        // Bad SymbolCategory string or non-positive width/height
                        // from the wire format — skip the offending entry but
                        // keep the rest of the pack.
                        continue
                    } catch (_: IllegalStateException) {
                        // Unknown SymbolCategory wire value (mapper raises with `error`).
                        continue
                    }
                // Per-symbol tier in the wire format is forward-compat; the
                // pack-level tier from the metadata mirror is authoritative
                // for v1 (every entry of a pack ships at the parent's tier).
                byId[definition.id] =
                    DownloadedEntry(
                        definition = definition,
                        tier = packsById[pack.id]?.tier ?: entry.tier,
                    )
            }
        }

        return DownloadedSnapshot.from(byId.values.toList())
    }

    private fun decodePayload(
        packId: String,
        payload: DownloadedPackPayload,
    ): SymbolPackPayload? {
        val decoded =
            try {
                json.decodeFromString<SymbolPackPayload>(payload.payloadJson)
            } catch (_: SerializationException) {
                // kotlinx.serialization parse failure — malformed JSON, missing
                // required fields, or a type mismatch the @Serializable
                // generated decoder rejects.
                return null
            } catch (_: IllegalArgumentException) {
                // SymbolPackPayload / SymbolPackPayloadEntry init `require()`
                // failure (non-positive version / schemaVersion / widthUnits /
                // heightUnits). Also the surface kotlinx.serialization may
                // raise on some invalid enum values depending on the
                // serializer path.
                return null
            }
        // Defense-in-depth — `request-pack-download` Edge Function envelope's
        // `current_version` and the payload body's own `version` SHOULD agree
        // (Phase 41.2b SymbolPackSyncManager surfaces a Failure.Parse if they
        // don't), but a bug elsewhere could land a payload row whose embedded
        // version disagrees with the row's version column. Reject the
        // mismatch rather than silently exposing the disagreement.
        if (decoded.packId != packId || decoded.version != payload.version) {
            return null
        }
        // Schema-version forward-compat guard. ADR-016 §3.4 mandates the
        // server splits to a new pack id on breaking schema changes, but
        // this client is the last line of defense if that discipline ever
        // lapses. A payload claiming a higher schemaVersion than this client
        // knows about is rejected — `Json { ignoreUnknownKeys = true }` would
        // otherwise silently produce a partially-populated SymbolDefinition
        // by ignoring removed fields.
        if (decoded.schemaVersion > SymbolPackPayload.CURRENT_SCHEMA_VERSION) {
            return null
        }
        return decoded
    }
}

/**
 * Immutable snapshot of every downloaded symbol resolved to its [SymbolDefinition]
 * plus the parent pack's [SymbolPackTier]. Built by [CompositeSymbolCatalog.refresh]
 * and read on every [SymbolCatalog.get] call.
 */
data class DownloadedSnapshot(
    val byId: Map<String, DownloadedEntry>,
    val byCategory: Map<SymbolCategory, List<DownloadedEntry>>,
    val allEntries: List<DownloadedEntry>,
) {
    companion object {
        val EMPTY: DownloadedSnapshot =
            DownloadedSnapshot(
                byId = emptyMap(),
                byCategory = emptyMap(),
                allEntries = emptyList(),
            )

        /**
         * Builds a snapshot from a pre-deduplicated entry list. Caller
         * (`CompositeSymbolCatalog.buildSnapshot`) already enforces last-
         * write-wins by symbol id via a `LinkedHashMap`. If [entries]
         * accidentally contains duplicate ids, `associateBy` will
         * silently drop all but the last occurrence — there is no guard
         * here because the caller's invariant is the only legitimate
         * construction path.
         */
        internal fun from(entries: List<DownloadedEntry>): DownloadedSnapshot {
            if (entries.isEmpty()) return EMPTY
            val byId = entries.associateBy { it.definition.id }
            val byCategory =
                entries
                    .groupBy { it.definition.category }
                    .mapValues { (_, list) -> list.sortedBy { it.definition.id } }
            return DownloadedSnapshot(
                byId = byId,
                byCategory = byCategory,
                allEntries = entries,
            )
        }
    }
}

/** A single downloaded symbol plus the tier of the pack that supplied it. */
data class DownloadedEntry(
    val definition: SymbolDefinition,
    val tier: SymbolPackTier,
)
