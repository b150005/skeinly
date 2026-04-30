package io.github.b150005.skeinly.data.remote

import kotlinx.coroutines.flow.StateFlow

expect class ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}
