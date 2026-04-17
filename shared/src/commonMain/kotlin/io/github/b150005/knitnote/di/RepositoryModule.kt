package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.data.local.LocalPatternDataSource
import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.local.LocalStructuredChartDataSource
import io.github.b150005.knitnote.data.realtime.RealtimeChannelProvider
import io.github.b150005.knitnote.data.realtime.SupabaseRealtimeChannelProvider
import io.github.b150005.knitnote.data.remote.ActivityDataSourceOperations
import io.github.b150005.knitnote.data.remote.CommentDataSourceOperations
import io.github.b150005.knitnote.data.remote.ConnectivityMonitor
import io.github.b150005.knitnote.data.remote.PublicPatternDataSource
import io.github.b150005.knitnote.data.remote.RemoteActivityDataSource
import io.github.b150005.knitnote.data.remote.RemoteCommentDataSource
import io.github.b150005.knitnote.data.remote.RemotePatternDataSource
import io.github.b150005.knitnote.data.remote.RemoteProgressDataSource
import io.github.b150005.knitnote.data.remote.RemoteProjectDataSource
import io.github.b150005.knitnote.data.remote.RemoteShareDataSource
import io.github.b150005.knitnote.data.remote.RemoteStorageDataSource
import io.github.b150005.knitnote.data.remote.RemoteStructuredChartDataSource
import io.github.b150005.knitnote.data.remote.RemoteUserDataSource
import io.github.b150005.knitnote.data.remote.ShareDataSourceOperations
import io.github.b150005.knitnote.data.remote.SupabaseConfig
import io.github.b150005.knitnote.data.remote.isConfigured
import io.github.b150005.knitnote.data.repository.ActivityRepositoryImpl
import io.github.b150005.knitnote.data.repository.AuthRepositoryImpl
import io.github.b150005.knitnote.data.repository.CommentRepositoryImpl
import io.github.b150005.knitnote.data.repository.OfflineUserRepository
import io.github.b150005.knitnote.data.repository.PatternRepositoryImpl
import io.github.b150005.knitnote.data.repository.ProgressRepositoryImpl
import io.github.b150005.knitnote.data.repository.ProjectRepositoryImpl
import io.github.b150005.knitnote.data.repository.ShareRepositoryImpl
import io.github.b150005.knitnote.data.repository.StructuredChartRepositoryImpl
import io.github.b150005.knitnote.data.repository.UserRepositoryImpl
import io.github.b150005.knitnote.domain.repository.ActivityRepository
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.CommentRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.domain.repository.ShareRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import io.github.b150005.knitnote.domain.repository.UserRepository
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.catalog.DefaultSymbolCatalog
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

val repositoryModule =
    module {
        // Auth
        single<AuthRepository> { AuthRepositoryImpl(getOrNull<SupabaseClient>()) }

        // Local data sources
        single { LocalProjectDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalProgressDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalPatternDataSource(get(), get(ioDispatcherQualifier)) }
        single { LocalStructuredChartDataSource(get(), get(ioDispatcherQualifier), get()) }

        // Remote data sources & repositories — only registered when Supabase is configured.
        // Consumers use getOrNull() to handle their absence in local-only mode.
        if (SupabaseConfig.isConfigured) {
            single { RemoteProjectDataSource(get<SupabaseClient>()) }
            single { RemoteProgressDataSource(get<SupabaseClient>()) }
            single { RemotePatternDataSource(get<SupabaseClient>()) }
            single<PublicPatternDataSource> { get<RemotePatternDataSource>() }
            single { RemoteStructuredChartDataSource(get<SupabaseClient>()) }
            single<ShareDataSourceOperations> { RemoteShareDataSource(get<SupabaseClient>()) }
            single { RemoteUserDataSource(get<SupabaseClient>()) }
            single<CommentDataSourceOperations> { RemoteCommentDataSource(get<SupabaseClient>()) }
            single<ActivityDataSourceOperations> { RemoteActivityDataSource(get<SupabaseClient>()) }
            single<StorageOperations>(chartImagesStorageQualifier) {
                RemoteStorageDataSource(get<SupabaseClient>(), "chart-images")
            }
            single<StorageOperations>(progressPhotosStorageQualifier) {
                RemoteStorageDataSource(get<SupabaseClient>(), "progress-photos")
            }
            single<RealtimeChannelProvider> { SupabaseRealtimeChannelProvider(get<SupabaseClient>()) }

            // Comment — remote-only with Realtime
            single<CommentRepository> {
                CommentRepositoryImpl(
                    remote = get<CommentDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Activity — remote-only with Realtime
            single<ActivityRepository> {
                ActivityRepositoryImpl(
                    remote = get<ActivityDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Share — remote-only
            single<ShareRepository> {
                ShareRepositoryImpl(
                    remote = get<ShareDataSourceOperations>(),
                    channelProvider = get<RealtimeChannelProvider>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
        }

        // Coordinator repositories (local-first + optional remote sync)
        single<ProjectRepository> {
            ProjectRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<ProgressRepository> {
            ProgressRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<PatternRepository> {
            PatternRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        single<StructuredChartRepository> {
            StructuredChartRepositoryImpl(
                local = get(),
                remote = getOrNull(),
                isOnline = get<ConnectivityMonitor>().isOnline,
                syncManager = get(),
                json = get(),
            )
        }
        // User profiles — remote with offline fallback
        single<UserRepository> {
            getOrNull<RemoteUserDataSource>()?.let { UserRepositoryImpl(it) }
                ?: OfflineUserRepository()
        }
        // Bundled knitting symbol catalog (Phase 30 + 31).
        single<SymbolCatalog> { DefaultSymbolCatalog.INSTANCE }
    }
