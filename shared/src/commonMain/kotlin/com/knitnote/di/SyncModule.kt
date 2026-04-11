package com.knitnote.di

import com.knitnote.data.local.LocalPatternDataSource
import com.knitnote.data.local.LocalPendingSyncDataSource
import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.realtime.RealtimeChannelProvider
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemotePatternDataSource
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import com.knitnote.data.sync.PendingSyncDataSource
import com.knitnote.data.sync.RealtimeSyncManager
import com.knitnote.data.sync.RemotePatternSyncOperations
import com.knitnote.data.sync.RemoteProgressSyncOperations
import com.knitnote.data.sync.RemoteProjectSyncOperations
import com.knitnote.data.sync.SyncExecutor
import com.knitnote.data.sync.SyncManager
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val syncModule =
    module {
        single { Json { ignoreUnknownKeys = true } }

        single<PendingSyncDataSource> { LocalPendingSyncDataSource(get(), get(ioDispatcherQualifier)) }

        // Sync interfaces backed by the remote data sources.
        // Only registered when Supabase is configured — consumers use getOrNull().
        if (SupabaseConfig.isConfigured) {
            single<RemoteProjectSyncOperations> { get<RemoteProjectDataSource>() }
            single<RemoteProgressSyncOperations> { get<RemoteProgressDataSource>() }
            single<RemotePatternSyncOperations> { get<RemotePatternDataSource>() }
        }

        single { SyncExecutor(getOrNull(), getOrNull(), getOrNull(), get()) }

        single<SyncManagerOperations> {
            SyncManager(
                pendingSyncDataSource = get(),
                syncExecutor = get(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                scope = get<CoroutineScope>(applicationScopeQualifier),
            ).also { it.start() }
        }

        // RealtimeSyncManager — only registered when Supabase is configured.
        // Consumers use getOrNull<RealtimeSyncManager>().
        if (SupabaseConfig.isConfigured) {
            single<RealtimeSyncManager> {
                RealtimeSyncManager(
                    channelProvider = get<RealtimeChannelProvider>(),
                    localProject = get<LocalProjectDataSource>(),
                    localProgress = get<LocalProgressDataSource>(),
                    localPattern = get<LocalPatternDataSource>(),
                    authRepository = get<AuthRepository>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                    isOnline = get<ConnectivityMonitor>().isOnline,
                ).also { it.start() }
            }
        }
    }
