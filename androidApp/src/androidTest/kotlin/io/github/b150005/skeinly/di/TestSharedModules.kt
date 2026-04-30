package io.github.b150005.skeinly.di

import io.github.b150005.skeinly.data.repository.OfflineUserRepository
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.PatternRepository
import io.github.b150005.skeinly.domain.repository.ProgressRepository
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import io.github.b150005.skeinly.domain.repository.UserRepository
import io.github.b150005.skeinly.domain.usecase.AddProgressNoteUseCase
import io.github.b150005.skeinly.domain.usecase.CompleteProjectUseCase
import io.github.b150005.skeinly.domain.usecase.CreateActivityUseCase
import io.github.b150005.skeinly.domain.usecase.CreateCommentUseCase
import io.github.b150005.skeinly.domain.usecase.CreateProjectUseCase
import io.github.b150005.skeinly.domain.usecase.DecrementRowUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteCommentUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteProgressNoteUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteProjectUseCase
import io.github.b150005.skeinly.domain.usecase.ForkSharedPatternUseCase
import io.github.b150005.skeinly.domain.usecase.GetActivitiesUseCase
import io.github.b150005.skeinly.domain.usecase.GetCommentsUseCase
import io.github.b150005.skeinly.domain.usecase.GetCurrentUserUseCase
import io.github.b150005.skeinly.domain.usecase.GetProgressNotesUseCase
import io.github.b150005.skeinly.domain.usecase.GetProjectsUseCase
import io.github.b150005.skeinly.domain.usecase.GetReceivedSharesUseCase
import io.github.b150005.skeinly.domain.usecase.IncrementRowUseCase
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.ReopenProjectUseCase
import io.github.b150005.skeinly.domain.usecase.ResolveShareTokenUseCase
import io.github.b150005.skeinly.domain.usecase.ShareProjectUseCase
import io.github.b150005.skeinly.domain.usecase.SignInUseCase
import io.github.b150005.skeinly.domain.usecase.SignOutUseCase
import io.github.b150005.skeinly.domain.usecase.SignUpUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateProfileUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateProjectUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateShareStatusUseCase
import io.github.b150005.skeinly.test.FakeAuthRepository
import io.github.b150005.skeinly.test.FakePatternRepository
import io.github.b150005.skeinly.test.FakeProgressRepository
import io.github.b150005.skeinly.test.FakeProjectRepository
import io.github.b150005.skeinly.test.FakeSyncManager
import io.github.b150005.skeinly.ui.activityfeed.ActivityFeedViewModel
import io.github.b150005.skeinly.ui.auth.AuthViewModel
import io.github.b150005.skeinly.ui.comments.CommentSectionViewModel
import io.github.b150005.skeinly.ui.profile.ProfileViewModel
import io.github.b150005.skeinly.ui.projectdetail.ProjectDetailViewModel
import io.github.b150005.skeinly.ui.projectlist.ProjectListViewModel
import io.github.b150005.skeinly.ui.sharedcontent.SharedContentViewModel
import io.github.b150005.skeinly.ui.sharedwithme.SharedWithMeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Test Koin modules that replace production modules with fakes.
 * Designed for local-only mode (no Supabase) to test UI interactions.
 *
 * All fake repositories use `single` scoped to the Koin container lifetime.
 * [io.github.b150005.skeinly.test.KoinTestRule] restarts Koin before each test,
 * ensuring fresh fakes per test.
 */
val testRepositoryModule =
    module {
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

val testUseCaseModule =
    module {
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
        factory {
            ForkSharedPatternUseCase(
                getOrNull(),
                get<PatternRepository>(),
                get<ProjectRepository>(),
                get<AuthRepository>(),
                getOrNull(),
            )
        }
        factory { UpdateShareStatusUseCase(getOrNull(), get<AuthRepository>()) }
    }

val testViewModelModule =
    module {
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

val testSharedModules =
    listOf(
        testRepositoryModule,
        testUseCaseModule,
        testViewModelModule,
    )
