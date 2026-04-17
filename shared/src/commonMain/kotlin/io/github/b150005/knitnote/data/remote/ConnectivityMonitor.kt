package io.github.b150005.knitnote.data.remote

import kotlinx.coroutines.flow.StateFlow

expect class ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}
