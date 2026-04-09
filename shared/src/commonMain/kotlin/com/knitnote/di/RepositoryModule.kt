package com.knitnote.di

import com.knitnote.data.local.LocalPatternDataSource
import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.remote.ConnectivityMonitor
import com.knitnote.data.remote.RemotePatternDataSource
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.data.remote.RemoteShareDataSource
import com.knitnote.data.remote.RemoteUserDataSource
import com.knitnote.data.repository.AuthRepositoryImpl
import com.knitnote.data.repository.PatternRepositoryImpl
import com.knitnote.data.repository.ProjectRepositoryImpl
import com.knitnote.data.repository.ProgressRepositoryImpl
import com.knitnote.data.repository.ShareRepositoryImpl
import com.knitnote.data.repository.UserRepositoryImpl
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ProgressRepository
import com.knitnote.domain.repository.ShareRepository
import com.knitnote.domain.repository.UserRepository
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val repositoryModule = module {
    // Auth
    single<AuthRepository> { AuthRepositoryImpl(getOrNull<SupabaseClient>()) }

    // Local data sources
    single { LocalProjectDataSource(get()) }
    single { LocalProgressDataSource(get()) }
    single { LocalPatternDataSource(get()) }

    // Remote data sources (nullable — only created when Supabase is configured)
    single<RemoteProjectDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteProjectDataSource(it) }
    }
    single<RemoteProgressDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteProgressDataSource(it) }
    }
    single<RemotePatternDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemotePatternDataSource(it) }
    }
    single<RemoteShareDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteShareDataSource(it) }
    }
    single<RemoteUserDataSource?> {
        getOrNull<SupabaseClient>()?.let { RemoteUserDataSource(it) }
    }

    // Coordinator repositories
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
    // User profiles — remote-only (nullable: cloud-only)
    single<UserRepository?> {
        getOrNull<RemoteUserDataSource>()?.let { UserRepositoryImpl(it) }
    }
    // Share is remote-only — requires Supabase (nullable: use cases handle absence)
    single<ShareRepository?> {
        val remoteDs = getOrNull<RemoteShareDataSource>() ?: return@single null
        val client = getOrNull<SupabaseClient>() ?: return@single null
        ShareRepositoryImpl(
            remote = remoteDs,
            supabaseClient = client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
