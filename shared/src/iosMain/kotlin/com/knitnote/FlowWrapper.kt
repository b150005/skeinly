package com.knitnote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Wraps a Kotlin [StateFlow] for consumption from Swift.
 * Swift calls [collect] to start observing, and [close] (via Closeable) to stop.
 */
class FlowWrapper<T : Any>(private val flow: StateFlow<T>) {

    val currentValue: T get() = flow.value

    fun collect(onEach: (T) -> Unit): Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            flow.collect { value ->
                onEach(value)
            }
        }
        return object : Closeable {
            override fun close() {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
}

/**
 * Wraps a regular Kotlin [Flow] (non-state) for one-shot event channels.
 * Swift calls [collect] and receives each emission.
 */
class EventFlowWrapper<T : Any>(private val flow: Flow<T>) {

    fun collect(onEach: (T) -> Unit): Closeable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            flow.collect { value ->
                onEach(value)
            }
        }
        return object : Closeable {
            override fun close() {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
}

/**
 * Simple closeable interface for Swift interop.
 */
fun interface Closeable {
    fun close()
}
