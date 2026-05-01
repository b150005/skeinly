package io.github.b150005.skeinly.data.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 39.3 (ADR-015 §6) — bounded FIFO ring of recently-tracked
 * [AnalyticsEvent]s, used by Phase 39.5 to attach a "last 10 events"
 * trail to every bug-report submission so triagers see what the user did
 * in the seconds leading up to the report.
 *
 * Wire contract preserved with [AnalyticsTracker]: this collector reads
 * the same `events` SharedFlow that the Application layer already
 * forwards into PostHog. The privacy-gating happens **upstream** at
 * `AnalyticsTrackerImpl.track()` — when opt-in is OFF, `tryEmit` returns
 * before any subscriber sees the event, so the ring buffer naturally
 * stays empty. Callers therefore never need to re-check opt-in state at
 * the snapshot site; the snapshot is always safe to attach because it
 * can only contain events the user has consented to.
 *
 * Capacity is fixed at [DEFAULT_CAPACITY] = 10 per ADR-015 §6 — large
 * enough to capture a typical interaction sequence (open detail → tap
 * mark complete → tap reopen → ...) without bloating GitHub Issue
 * bodies.
 *
 * Thread-safety: [Mutex] serializes the FIFO mutation. The collector
 * coroutine is the sole writer; [snapshot] readers serialize through
 * the same mutex. [AnalyticsEvent] is immutable so the returned list is
 * safe to share.
 *
 * Lifecycle: [start] is fire-once. The collector launches into the
 * supplied [CoroutineScope] (Application-lifetime in production —
 * `SkeinlyApplication.applicationScope` on Android, the iOS init-time
 * detached scope on iOS) and never completes; the scope's cancellation
 * tears it down.
 */
class EventRingBuffer(
    private val tracker: AnalyticsTracker,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "EventRingBuffer capacity must be positive, was $capacity" }
    }

    private val mutex = Mutex()

    // ArrayDeque.removeFirst()/addLast() give O(1) amortized FIFO. Bounded
    // by the [capacity] check inside the collector — we never let it grow
    // beyond N entries.
    private val buffer: ArrayDeque<AnalyticsEvent> = ArrayDeque(capacity)

    private var started = false

    /**
     * Subscribes to [tracker]'s events SharedFlow on [scope] and
     * accumulates emissions into the FIFO ring. Idempotent — a second
     * call on the same instance is a silent no-op so an Application
     * restart on Android (process survives but onCreate re-runs in some
     * rare lifecycle paths) does not double-subscribe and double-record
     * every event.
     *
     * Concurrency contract: this fn is **not** thread-safe — production
     * call sites are Application-layer single-threaded init paths
     * (`SkeinlyApplication.onCreate` on Android, `iOSApp.init` on iOS).
     * Concurrent calls are out of scope.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            tracker.events.collect { event ->
                mutex.withLock {
                    if (buffer.size >= capacity) {
                        buffer.removeFirst()
                    }
                    buffer.addLast(event)
                }
            }
        }
    }

    /**
     * Returns an immutable snapshot of the buffer in arrival order
     * (oldest first). The list copy is taken under [mutex] so it is
     * consistent with the writer; subsequent emissions do not mutate
     * the returned list.
     *
     * Phase 39.5 callers serialize the snapshot into the bug-report
     * Markdown body via [AnalyticsEvent.name] / [AnalyticsEvent.properties]
     * — never the full toString, which would leak Kotlin class names.
     */
    suspend fun snapshot(): List<AnalyticsEvent> = mutex.withLock { buffer.toList() }

    companion object {
        const val DEFAULT_CAPACITY: Int = 10
    }
}
