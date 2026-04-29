package io.github.b150005.knitnote.di

import io.github.b150005.knitnote.domain.usecase.AddProgressNoteUseCase
import io.github.b150005.knitnote.domain.usecase.ClosePullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.CloseRealtimeChannelsUseCase
import io.github.b150005.knitnote.domain.usecase.CompleteOnboardingUseCase
import io.github.b150005.knitnote.domain.usecase.CompleteProjectUseCase
import io.github.b150005.knitnote.domain.usecase.CreateActivityUseCase
import io.github.b150005.knitnote.domain.usecase.CreateBranchUseCase
import io.github.b150005.knitnote.domain.usecase.CreateCommentUseCase
import io.github.b150005.knitnote.domain.usecase.CreatePatternUseCase
import io.github.b150005.knitnote.domain.usecase.CreateProjectUseCase
import io.github.b150005.knitnote.domain.usecase.CreateStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.DecrementRowUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteAccountUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteChartImageUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteCommentUseCase
import io.github.b150005.knitnote.domain.usecase.DeletePatternUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProgressNoteUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProgressPhotoUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProjectUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.ForkPublicPatternUseCase
import io.github.b150005.knitnote.domain.usecase.ForkSharedPatternUseCase
import io.github.b150005.knitnote.domain.usecase.GetActivitiesUseCase
import io.github.b150005.knitnote.domain.usecase.GetChartBranchesUseCase
import io.github.b150005.knitnote.domain.usecase.GetChartDiffUseCase
import io.github.b150005.knitnote.domain.usecase.GetChartHistoryUseCase
import io.github.b150005.knitnote.domain.usecase.GetChartRevisionUseCase
import io.github.b150005.knitnote.domain.usecase.GetCommentsUseCase
import io.github.b150005.knitnote.domain.usecase.GetCurrentUserUseCase
import io.github.b150005.knitnote.domain.usecase.GetIncomingPullRequestsUseCase
import io.github.b150005.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import io.github.b150005.knitnote.domain.usecase.GetOutgoingPullRequestsUseCase
import io.github.b150005.knitnote.domain.usecase.GetPatternsUseCase
import io.github.b150005.knitnote.domain.usecase.GetProgressNotesUseCase
import io.github.b150005.knitnote.domain.usecase.GetProjectByIdUseCase
import io.github.b150005.knitnote.domain.usecase.GetProjectsUseCase
import io.github.b150005.knitnote.domain.usecase.GetPublicPatternsUseCase
import io.github.b150005.knitnote.domain.usecase.GetPullRequestCommentsUseCase
import io.github.b150005.knitnote.domain.usecase.GetPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.GetReceivedSharesUseCase
import io.github.b150005.knitnote.domain.usecase.GetStructuredChartByPatternIdUseCase
import io.github.b150005.knitnote.domain.usecase.IncrementRowUseCase
import io.github.b150005.knitnote.domain.usecase.MarkRowSegmentsDoneUseCase
import io.github.b150005.knitnote.domain.usecase.MarkSegmentDoneUseCase
import io.github.b150005.knitnote.domain.usecase.MergePullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.OpenPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.PostPullRequestCommentUseCase
import io.github.b150005.knitnote.domain.usecase.ReopenProjectUseCase
import io.github.b150005.knitnote.domain.usecase.ResetProjectProgressUseCase
import io.github.b150005.knitnote.domain.usecase.ResolveShareTokenUseCase
import io.github.b150005.knitnote.domain.usecase.RestoreRevisionUseCase
import io.github.b150005.knitnote.domain.usecase.SendPasswordResetUseCase
import io.github.b150005.knitnote.domain.usecase.ShareProjectUseCase
import io.github.b150005.knitnote.domain.usecase.SignInUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
import io.github.b150005.knitnote.domain.usecase.SignUpUseCase
import io.github.b150005.knitnote.domain.usecase.SwitchBranchUseCase
import io.github.b150005.knitnote.domain.usecase.ToggleSegmentStateUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateEmailUseCase
import io.github.b150005.knitnote.domain.usecase.UpdatePasswordUseCase
import io.github.b150005.knitnote.domain.usecase.UpdatePatternUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateProfileUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateProjectUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateShareStatusUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.UploadAvatarUseCase
import io.github.b150005.knitnote.domain.usecase.UploadChartImageUseCase
import io.github.b150005.knitnote.domain.usecase.UploadProgressPhotoUseCase
import org.koin.dsl.module

val useCaseModule =
    module {
        // Onboarding
        factory { GetOnboardingCompletedUseCase(get()) }
        factory { CompleteOnboardingUseCase(get()) }

        factory { ObserveAuthStateUseCase(get()) }
        factory { SignInUseCase(get()) }
        factory { SignUpUseCase(get()) }
        factory { CloseRealtimeChannelsUseCase(getOrNull(), getOrNull(), getOrNull()) }
        factory { SignOutUseCase(get(), get()) }
        factory { DeleteAccountUseCase(get(), get()) }
        factory { SendPasswordResetUseCase(get()) }
        factory { UpdatePasswordUseCase(get()) }
        factory { UpdateEmailUseCase(get()) }
        factory { GetProjectsUseCase(get(), get()) }
        factory { CreateProjectUseCase(get(), get(), getOrNull()) }
        factory { IncrementRowUseCase(get()) }
        factory { DecrementRowUseCase(get()) }
        factory { GetProjectByIdUseCase(get()) }
        factory { DeleteProjectUseCase(get()) }
        factory { AddProgressNoteUseCase(get(), get()) }
        factory { GetProgressNotesUseCase(get()) }
        factory { DeleteProgressNoteUseCase(get()) }
        factory { UpdateProjectUseCase(get()) }
        factory { CompleteProjectUseCase(get(), getOrNull()) }
        factory { ReopenProjectUseCase(get()) }

        // Pattern use cases
        factory { GetPatternsUseCase(get(), get()) }
        factory { CreatePatternUseCase(get(), get(), getOrNull()) }
        factory { UpdatePatternUseCase(get()) }
        factory { DeletePatternUseCase(get()) }

        // Chart image use cases (RemoteStorageDataSource is nullable — only with Supabase)
        factory { UploadChartImageUseCase(get(), getOrNull(chartImagesStorageQualifier), get()) }
        factory { DeleteChartImageUseCase(get(), getOrNull(chartImagesStorageQualifier)) }

        // Progress photo use cases
        factory { UploadProgressPhotoUseCase(getOrNull(progressPhotosStorageQualifier), get()) }
        factory { DeleteProgressPhotoUseCase(getOrNull(progressPhotosStorageQualifier), get()) }

        // Avatar use case (Phase C)
        factory { UploadAvatarUseCase(getOrNull(avatarsStorageQualifier), get()) }

        // Profile use cases (UserRepository with offline fallback)
        factory { GetCurrentUserUseCase(get(), get()) }
        factory { UpdateProfileUseCase(get(), get()) }

        // Activity use cases (ActivityRepository is nullable — only available with Supabase)
        factory { CreateActivityUseCase(getOrNull()) }
        factory { GetActivitiesUseCase(getOrNull()) }

        // Comment use cases (CommentRepository is nullable — only available with Supabase)
        factory { GetCommentsUseCase(getOrNull()) }
        factory { CreateCommentUseCase(getOrNull(), get(), getOrNull()) }
        factory { DeleteCommentUseCase(getOrNull(), get()) }

        // Share use cases (ShareRepository is nullable — only available with Supabase)
        factory { ShareProjectUseCase(get(), get(), getOrNull(), get(), getOrNull()) }
        factory { ResolveShareTokenUseCase(getOrNull(), get(), get()) }
        factory { GetReceivedSharesUseCase(getOrNull(), get()) }
        factory { ForkSharedPatternUseCase(getOrNull(), get(), get(), get(), getOrNull()) }
        factory { UpdateShareStatusUseCase(getOrNull(), get()) }

        // Discovery use cases (PublicPatternDataSource is nullable — only with Supabase)
        factory { GetPublicPatternsUseCase(getOrNull()) }
        factory { ForkPublicPatternUseCase(get(), get(), get(), get(), getOrNull()) }

        // Structured chart use cases (Phase 29)
        factory { GetStructuredChartByPatternIdUseCase(get()) }
        factory { ObserveStructuredChartUseCase(get()) }
        factory { CreateStructuredChartUseCase(get(), get(), get()) }
        factory { UpdateStructuredChartUseCase(get(), get()) }
        factory { DeleteStructuredChartUseCase(get()) }

        // Phase 37.2 chart-history use cases (ADR-013 §4). Both ship together
        // so the Phase 37.3 ChartDiffScreen load path can `get(revisionId)`
        // without further DI churn.
        factory { GetChartHistoryUseCase(get()) }
        factory { GetChartRevisionUseCase(get()) }

        // Phase 37.3 chart-diff use case (ADR-013 §5).
        factory { GetChartDiffUseCase(get()) }

        // Phase 37.4 branch + restore use cases (ADR-013 §6, §7).
        factory { GetChartBranchesUseCase(get()) }
        factory { CreateBranchUseCase(get(), get()) }
        factory { SwitchBranchUseCase(get(), get(), get()) }
        factory { RestoreRevisionUseCase(get(), get()) }

        // Phase 38.2 pull-request list (ADR-014 §6, §8). PullRequestRepository
        // is unconditionally registered (handles local-only mode internally
        // via `remote = null`), so non-null here matches GetChartHistoryUseCase.
        factory { GetIncomingPullRequestsUseCase(get()) }
        factory { GetOutgoingPullRequestsUseCase(get()) }

        // Phase 38.3 pull-request detail use cases (ADR-014 §6, §8).
        factory { GetPullRequestUseCase(get()) }
        factory { GetPullRequestCommentsUseCase(get()) }
        factory { PostPullRequestCommentUseCase(get(), get()) }
        factory { ClosePullRequestUseCase(get(), get()) }
        // OpenPullRequestUseCase needs the chart-revision repo for the
        // common-ancestor walk per ADR-014 §3.
        factory { OpenPullRequestUseCase(get(), get(), get()) }

        // Phase 38.4 merge use case (ADR-014 §5). Routes through the
        // SECURITY DEFINER `merge_pull_request` RPC; bypasses the standard
        // local-then-sync orchestration since the RPC is the only writer
        // permitted to produce author_id != owner_id rows.
        // `getOrNull<PullRequestMergeOperations>()` so local-only mode (no
        // Supabase) surfaces a Validation error rather than a NPE on first
        // tap — see MergePullRequestUseCase.invoke for the offline branch.
        factory {
            MergePullRequestUseCase(
                mergeOperations = getOrNull<io.github.b150005.knitnote.domain.repository.PullRequestMergeOperations>(),
                patternRepository = get(),
                authRepository = get(),
                json = get(),
            )
        }

        // Per-segment progress use cases (Phase 34)
        factory { ObserveProjectSegmentsUseCase(get()) }
        factory { ToggleSegmentStateUseCase(get(), getOrNull()) }
        factory { MarkSegmentDoneUseCase(get(), getOrNull()) }
        factory { ResetProjectProgressUseCase(get()) }
        // Phase 35.2c — batch "mark row done" (ADR-011 §4). Pre-registered so the
        // first Phase 35.2d ViewModel consumer can `get()` without touching DI.
        factory { MarkRowSegmentsDoneUseCase(get(), get(), getOrNull()) }
    }
