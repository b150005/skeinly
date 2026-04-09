package com.knitnote.di

import com.knitnote.data.local.LocalPendingSyncDataSource
import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.sync.PendingSyncDataSource
import com.knitnote.data.sync.RealtimeSyncManager
import com.knitnote.data.sync.RemoteProgressSyncOperations
import com.knitnote.data.sync.RemoteProjectSyncOperations
import com.knitnote.data.sync.SyncExecutor
import com.knitnote.data.sync.SyncManager
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val syncModule = module {
    single { Json { ignoreUnknownKeys = true } }

    single<PendingSyncDataSource> { LocalPendingSyncDataSource(get()) }

    // Sync interfaces backed by the remote data sources
    single<RemoteProjectSyncOperations?> { getOrNull<RemoteProjectDataSource>() }
    single<RemoteProgressSyncOperations?> { getOrNull<RemoteProgressDataSource>() }

    single { SyncExecutor(getOrNull(), getOrNull(), get()) }

    single<SyncManagerOperations> {
        SyncManager(
            pendingSyncDataSource = get(),
            syncExecutor = get(),
            isOnline = get<ConnectivityMonitor>().isOnline,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { it.start() }
    }

    single<RealtimeSyncManager?> {
        val client = getOrNull<SupabaseClient>() ?: return@single null
        RealtimeSyncManager(
            supabaseClient = client,
            localProject = get<LocalProjectDataSource>(),
            localProgress = get<LocalProgressDataSource>(),
            authRepository = get<AuthRepository>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).also { it.start() }
    }
}
