package com.knitnote.di

import com.knitnote.data.local.LocalPatternDataSource
import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemoteActivityDataSource
import com.knitnote.data.remote.RemoteCommentDataSource
import com.knitnote.data.remote.RemotePatternDataSource
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.remote.RemoteShareDataSource
import com.knitnote.data.remote.RemoteStorageDataSource
import com.knitnote.data.remote.RemoteUserDataSource
import com.knitnote.domain.repository.StorageOperations
import com.knitnote.data.remote.SupabaseConfig
import com.knitnote.data.remote.isConfigured
import com.knitnote.data.repository.ActivityRepositoryImpl
import com.knitnote.data.repository.AuthRepositoryImpl
import com.knitnote.data.repository.CommentRepositoryImpl
import com.knitnote.data.repository.OfflineUserRepository
import com.knitnote.data.repository.PatternRepositoryImpl
import com.knitnote.data.repository.ProgressRepositoryImpl
import com.knitnote.data.repository.ProjectRepositoryImpl
import com.knitnote.data.repository.ShareRepositoryImpl
import com.knitnote.data.repository.UserRepositoryImpl
import com.knitnote.domain.repository.ActivityRepository
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.CommentRepository
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProgressRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ShareRepository
import com.knitnote.domain.repository.UserRepository
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

        // Remote data sources & repositories — only registered when Supabase is configured.
        // Consumers use getOrNull() to handle their absence in local-only mode.
        if (SupabaseConfig.isConfigured) {
            single { RemoteProjectDataSource(get<SupabaseClient>()) }
            single { RemoteProgressDataSource(get<SupabaseClient>()) }
            single { RemotePatternDataSource(get<SupabaseClient>()) }
            single { RemoteShareDataSource(get<SupabaseClient>()) }
            single { RemoteUserDataSource(get<SupabaseClient>()) }
            single { RemoteCommentDataSource(get<SupabaseClient>()) }
            single { RemoteActivityDataSource(get<SupabaseClient>()) }
            single<StorageOperations> { RemoteStorageDataSource(get<SupabaseClient>()) }

            // Comment — remote-only with Realtime
            single<CommentRepository> {
                CommentRepositoryImpl(
                    remote = get<RemoteCommentDataSource>(),
                    supabaseClient = get<SupabaseClient>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Activity — remote-only with Realtime
            single<ActivityRepository> {
                ActivityRepositoryImpl(
                    remote = get<RemoteActivityDataSource>(),
                    supabaseClient = get<SupabaseClient>(),
                    scope = get<CoroutineScope>(applicationScopeQualifier),
                )
            }
            // Share — remote-only
            single<ShareRepository> {
                ShareRepositoryImpl(
                    remote = get<RemoteShareDataSource>(),
                    supabaseClient = get<SupabaseClient>(),
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
        // User profiles — remote with offline fallback
        single<UserRepository> {
            getOrNull<RemoteUserDataSource>()?.let { UserRepositoryImpl(it) }
                ?: OfflineUserRepository()
        }
    }
