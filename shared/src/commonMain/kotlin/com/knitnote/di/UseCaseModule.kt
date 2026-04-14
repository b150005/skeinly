package com.knitnote.di

import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CloseRealtimeChannelsUseCase
import com.knitnote.domain.usecase.CompleteOnboardingUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.CreateActivityUseCase
import com.knitnote.domain.usecase.CreateCommentUseCase
import com.knitnote.domain.usecase.CreatePatternUseCase
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteAccountUseCase
import com.knitnote.domain.usecase.DeleteChartImageUseCase
import com.knitnote.domain.usecase.DeleteCommentUseCase
import com.knitnote.domain.usecase.DeletePatternUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.DeleteProgressPhotoUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.ForkSharedPatternUseCase
import com.knitnote.domain.usecase.GetActivitiesUseCase
import com.knitnote.domain.usecase.GetCommentsUseCase
import com.knitnote.domain.usecase.GetCurrentUserUseCase
import com.knitnote.domain.usecase.GetOnboardingCompletedUseCase
import com.knitnote.domain.usecase.GetPatternsUseCase
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.GetProjectByIdUseCase
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
import com.knitnote.domain.usecase.UpdatePatternUseCase
import com.knitnote.domain.usecase.UpdateProfileUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UpdateShareStatusUseCase
import com.knitnote.domain.usecase.UploadChartImageUseCase
import com.knitnote.domain.usecase.UploadProgressPhotoUseCase
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
    }
