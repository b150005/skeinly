package com.knitnote.android.di

import com.knitnote.android.test.FakeAuthRepository
import com.knitnote.android.test.FakePatternRepository
import com.knitnote.android.test.FakeProgressRepository
import com.knitnote.android.test.FakeProjectRepository
import com.knitnote.android.test.FakeSyncManager
import com.knitnote.data.repository.OfflineUserRepository
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.PatternRepository
import com.knitnote.domain.repository.ProjectRepository
import com.knitnote.domain.repository.ProgressRepository
import com.knitnote.domain.repository.UserRepository
import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.CreateActivityUseCase
import com.knitnote.domain.usecase.CreateCommentUseCase
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteCommentUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.ForkSharedPatternUseCase
import com.knitnote.domain.usecase.GetActivitiesUseCase
import com.knitnote.domain.usecase.GetCommentsUseCase
import com.knitnote.domain.usecase.GetCurrentUserUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.GetReceivedSharesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.ObserveAuthStateUseCase
import com.knitnote.domain.usecase.ReopenProjectUseCase
import com.knitnote.domain.usecase.ResolveShareTokenUseCase
import com.knitnote.domain.usecase.ShareProjectUseCase
import com.knitnote.domain.usecase.SignInUseCase
import com.knitnote.domain.usecase.SignOutUseCase
import com.knitnote.domain.usecase.SignUpUseCase
import com.knitnote.domain.usecase.UpdateProfileUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UpdateShareStatusUseCase
import com.knitnote.ui.activityfeed.ActivityFeedViewModel
import com.knitnote.ui.auth.AuthViewModel
import com.knitnote.ui.comments.CommentSectionViewModel
import com.knitnote.ui.profile.ProfileViewModel
import com.knitnote.ui.projectdetail.ProjectDetailViewModel
import com.knitnote.ui.projectlist.ProjectListViewModel
import com.knitnote.ui.sharedcontent.SharedContentViewModel
import com.knitnote.ui.sharedwithme.SharedWithMeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Test Koin modules that replace production modules with fakes.
 * Designed for local-only mode (no Supabase) to test UI interactions.
 *
 * All fake repositories use `single` scoped to the Koin container lifetime.
 * [com.knitnote.android.test.KoinTestRule] restarts Koin before each test,
 * ensuring fresh fakes per test.
 */
val testRepositoryModule = module {
    single<AuthRepository> { FakeAuthRepository() }
    single<ProjectRepository> { FakeProjectRepository() }
    single<ProgressRepository> { FakeProgressRepository() }
    single<PatternRepository> { FakePatternRepository() }

    // Remote-only repositories (Share, Comment, Activity) are intentionally
    // NOT registered — UseCases use getOrNull() to handle their absence.

    // UserRepository — offline fallback
    single<UserRepository> { OfflineUserRepository() }

    // SyncManager — no-op
    single<SyncManagerOperations> { FakeSyncManager() }
}

val testUseCaseModule = module {
    factory { ObserveAuthStateUseCase(get<AuthRepository>()) }
    factory { SignInUseCase(get<AuthRepository>()) }
    factory { SignUpUseCase(get<AuthRepository>()) }
    factory { SignOutUseCase(get(), getOrNull(), getOrNull(), getOrNull()) }
    factory { GetProjectsUseCase(get<ProjectRepository>(), get<AuthRepository>()) }
    factory { CreateProjectUseCase(get<ProjectRepository>(), get<AuthRepository>(), getOrNull()) }
    factory { IncrementRowUseCase(get<ProjectRepository>()) }
    factory { DecrementRowUseCase(get<ProjectRepository>()) }
    factory { DeleteProjectUseCase(get<ProjectRepository>()) }
    factory { AddProgressNoteUseCase(get<ProgressRepository>()) }
    factory { GetProgressNotesUseCase(get<ProgressRepository>()) }
    factory { DeleteProgressNoteUseCase(get<ProgressRepository>()) }
    factory { UpdateProjectUseCase(get<ProjectRepository>()) }
    factory { CompleteProjectUseCase(get<ProjectRepository>(), getOrNull()) }
    factory { ReopenProjectUseCase(get<ProjectRepository>()) }
    factory { GetCurrentUserUseCase(get<AuthRepository>(), get<UserRepository>()) }
    factory { UpdateProfileUseCase(get<AuthRepository>(), get<UserRepository>()) }
    factory { CreateActivityUseCase(getOrNull()) }
    factory { GetActivitiesUseCase(getOrNull()) }
    factory { GetCommentsUseCase(getOrNull()) }
    factory { CreateCommentUseCase(getOrNull(), get<AuthRepository>(), getOrNull()) }
    factory { DeleteCommentUseCase(getOrNull(), get<AuthRepository>()) }
    factory { ShareProjectUseCase(get<ProjectRepository>(), get<PatternRepository>(), getOrNull(), get<AuthRepository>(), getOrNull()) }
    factory { ResolveShareTokenUseCase(getOrNull(), get<PatternRepository>(), get<ProjectRepository>()) }
    factory { GetReceivedSharesUseCase(getOrNull(), get<AuthRepository>()) }
    factory { ForkSharedPatternUseCase(getOrNull(), get<PatternRepository>(), get<ProjectRepository>(), get<AuthRepository>(), getOrNull()) }
    factory { UpdateShareStatusUseCase(getOrNull(), get<AuthRepository>()) }
}

val testViewModelModule = module {
    viewModelOf(::AuthViewModel)
    viewModelOf(::ProfileViewModel)
    viewModel { ActivityFeedViewModel(get(), get(), get()) }
    viewModelOf(::ProjectListViewModel)
    viewModel { params ->
        ProjectDetailViewModel(
            projectId = params.get(),
            projectRepository = get(),
            incrementRow = get(),
            decrementRow = get(),
            addProgressNote = get(),
            getProgressNotes = get(),
            deleteProgressNote = get(),
            updateProject = get(),
            completeProject = get(),
            reopenProject = get(),
            shareProject = get(),
        )
    }
    viewModel { SharedWithMeViewModel(get(), get(), get(), get()) }
    viewModel { params ->
        CommentSectionViewModel(
            targetType = params.get(),
            targetId = params.get(),
            getComments = get(),
            createComment = get(),
            deleteCommentUseCase = get(),
            userRepository = get(),
        )
    }
    viewModel { params ->
        SharedContentViewModel(
            token = params.get<String?>(0),
            shareId = params.get<String?>(1),
            resolveShareToken = get(),
            forkSharedPattern = get(),
        )
    }
}

val testSharedModules = listOf(
    testRepositoryModule,
    testUseCaseModule,
    testViewModelModule,
)
