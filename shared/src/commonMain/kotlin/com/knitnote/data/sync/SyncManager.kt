package com.knitnote.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlin.math.min
import kotlin.time.Clock

class SyncManager(
    private val pendingSyncDataSource: PendingSyncDataSource,
    private val syncExecutor: SyncExecutor,
    private val isOnline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val config: SyncConfig = SyncConfig(),
    private val clock: Clock = Clock.System,
) : SyncManagerOperations {

    private val processingMutex = Mutex()
    private val coalescingMutex = Mutex()
    private var monitorJob: Job? = null

    /**
     * Start monitoring connectivity. When transitioning to online, process the queue.
     */
    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            isOnline.collect { online ->
                if (online) {
                    drainWithRetry()
                }
            }
        }
    }

    /**
     * Repeatedly processes the queue until empty or all remaining entries
     * have failed. Applies exponential backoff between rounds.
     */
    private suspend fun drainWithRetry() {
        var retryRound = 0
        while (true) {
            val pendingBefore = pendingSyncDataSource.countPending()
            if (pendingBefore == 0L) break

            processQueue()

            val pendingAfter = pendingSyncDataSource.countPending()
            if (pendingAfter == 0L) break

            // If pending count didn't change, entries are failing — back off
            if (pendingAfter >= pendingBefore) {
                retryRound++
                if (retryRound > config.maxRetries) break
                delay(calculateBackoff(retryRound - 1))
            } else {
                retryRound = 0 // progress was made, reset
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Try immediate sync if online; otherwise enqueue for later.
     * Called by repositories after a local write.
     */
    override suspend fun syncOrEnqueue(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
    ) {
        if (isOnline.value) {
            val entry = PendingSyncEntry(
                id = 0,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                createdAt = clock.now().toEpochMilliseconds(),
                retryCount = 0,
                status = "pending",
            )
            try {
                syncExecutor.execute(entry)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // remote failed, fall through to enqueue
            }
        }

        enqueueWithCoalescing(entityType, entityId, operation, payload)
    }

    /**
     * Coalesce pending entries for the same entity to avoid queue bloat.
     * Guarded by [coalescingMutex] to prevent TOCTOU races.
     *
     * Rules:
     * - insert + update → keep insert with latest payload
     * - insert + delete → remove both (entity was never synced)
     * - update + update → keep earlier entry with latest payload
     * - update + delete → replace with delete
     * - no existing → enqueue new entry
     */
    private suspend fun enqueueWithCoalescing(
        entityType: String,
        entityId: String,
        operation: String,
        payload: String,
    ) {
        coalescingMutex.withLock {
            val existing = pendingSyncDataSource.getByEntityId(entityId)
                .filter { it.entityType == entityType }

            if (existing.isEmpty()) {
                pendingSyncDataSource.enqueue(entityType, entityId, operation, payload, clock.now().toEpochMilliseconds())
                return
            }

            val prior = existing.first()

            when {
                prior.operation == "insert" && operation == "delete" -> {
                    pendingSyncDataSource.delete(prior.id)
                }
                prior.operation == "insert" && operation == "update" -> {
                    pendingSyncDataSource.updatePayload(prior.id, payload)
                }
                operation == "delete" -> {
                    pendingSyncDataSource.delete(prior.id)
                    pendingSyncDataSource.enqueue(entityType, entityId, "delete", "", clock.now().toEpochMilliseconds())
                }
                else -> {
                    pendingSyncDataSource.updatePayload(prior.id, payload)
                }
            }
        }
    }

    /**
     * Process all pending entries in FIFO order.
     * On failure, increments retry count and applies exponential backoff
     * **outside** the processing lock to avoid blocking concurrent operations.
     */
    suspend fun processQueue() {
        processingMutex.withLock {
            val entries = pendingSyncDataSource.getAllPending()
            for (entry in entries) {
                if (entry.retryCount >= config.maxRetries) {
                    pendingSyncDataSource.markFailed(entry.id)
                    continue
                }

                try {
                    syncExecutor.execute(entry)
                    pendingSyncDataSource.delete(entry.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SerializationException) {
                    pendingSyncDataSource.markFailed(entry.id)
                } catch (_: Exception) {
                    pendingSyncDataSource.incrementRetry(entry.id)
                    // Stop processing this batch — remaining entries will be
                    // retried on the next processQueue invocation (connectivity
                    // change or manual trigger). This avoids holding the mutex
                    // during backoff delays.
                    break
                }
            }
        }
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val delayMs = config.baseDelayMs * (1L shl retryCount.coerceAtMost(20))
        return min(delayMs, config.maxDelayMs)
    }
}
