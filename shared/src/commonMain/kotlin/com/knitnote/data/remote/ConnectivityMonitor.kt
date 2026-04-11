package com.knitnote.data.remote

import kotlinx.coroutines.flow.StateFlow

expect class ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
}
