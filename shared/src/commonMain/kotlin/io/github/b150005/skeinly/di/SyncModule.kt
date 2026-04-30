package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.local.LocalChartRevisionDataSource
import io.github.b150005.skeinly.data.local.LocalPatternDataSource
import io.github.b150005.skeinly.data.local.LocalPendingSyncDataSource
import io.github.b150005.skeinly.data.local.LocalProgressDataSource
import io.github.b150005.skeinly.data.local.LocalProjectDataSource
import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.local.LocalPullRequestDataSource
import io.github.b150005.skeinly.data.realtime.RealtimeChannelProvider
import io.github.b150005.skeinly.data.remote.ConnectivityMonitor
import io.github.b150005.skeinly.data.remote.RemoteChartBranchDataSource
import io.github.b150005.skeinly.data.remote.RemoteChartRevisionDataSource
import io.github.b150005.skeinly.data.remote.RemotePatternDataSource
import io.github.b150005.skeinly.data.remote.RemoteProgressDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectDataSource
import io.github.b150005.skeinly.data.remote.RemoteProjectSegmentDataSource
import io.github.b150005.skeinly.data.remote.RemotePullRequestDataSource
import io.github.b150005.skeinly.data.remote.RemoteStructuredChartDataSource
import io.github.b150005.skeinly.data.remote.SupabaseConfig
import io.github.b150005.skeinly.data.remote.isConfigured
import io.github.b150005.skeinly.data.sync.PendingSyncDataSource
import io.github.b150005.skeinly.data.sync.RealtimeSyncManager
import io.github.b150005.skeinly.data.sync.RemoteChartBranchSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteChartRevisionSyncOperations
import io.github.b150005.skeinly.data.sync.RemotePatternSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProgressSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProjectSegmentSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteProjectSyncOperations
import io.github.b150005.skeinly.data.sync.RemotePullRequestCommentSyncOperations
import io.github.b150005.skeinly.data.sync.RemotePullRequestSyncOperations
import io.github.b150005.skeinly.data.sync.RemoteStructuredChartSyncOperations
import io.github.b150005.skeinly.data.sync.SyncExecutor
import io.github.b150005.skeinly.data.sync.SyncManager
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.domain.repository.AuthRepository
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
            single<RemoteStructuredChartSyncOperations> { get<RemoteStructuredChartDataSource>() }
            single<RemoteProjectSegmentSyncOperations> { get<RemoteProjectSegmentDataSource>() }
            single<RemoteChartRevisionSyncOperations> { get<RemoteChartRevisionDataSource>() }
            single<RemoteChartBranchSyncOperations> { get<RemoteChartBranchDataSource>() }
            single<RemotePullRequestSyncOperations> { get<RemotePullRequestDataSource>() }
            single<RemotePullRequestCommentSyncOperations> { get<RemotePullRequestDataSource>() }
        }

        single {
            SyncExecutor(
                remoteProject = getOrNull(),
                remoteProgress = getOrNull(),
                remotePattern = getOrNull(),
                remoteStructuredChart = getOrNull(),
                json = get(),
                remoteProjectSegment = getOrNull(),
                remoteChartRevision = getOrNull(),
                remoteChartBranch = getOrNull(),
                remotePullRequest = getOrNull(),
                remotePullRequestComment = getOrNull(),
            )
        }

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
                    localProjectSegment = get<LocalProjectSegmentDataSource>(),
                    authRepository = get<AuthRepository>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                    isOnline = get<ConnectivityMonitor>().isOnline,
                    localChartRevision = get<LocalChartRevisionDataSource>(),
                    localPullRequest = get<LocalPullRequestDataSource>(),
                ).also { it.start() }
            }
        }
    }
