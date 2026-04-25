package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.repository.ChartBranchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class FakeChartBranchRepository : ChartBranchRepository {
    private val branches = MutableStateFlow<List<ChartBranch>>(emptyList())

    var failNext: Throwable? = null

    override suspend fun getByPatternIdAndName(
        patternId: String,
        branchName: String,
    ): ChartBranch? {
        failNext?.let { throw it.also { failNext = null } }
        return branches.value.firstOrNull { it.patternId == patternId && it.branchName == branchName }
    }

    override suspend fun getByPatternId(patternId: String): List<ChartBranch> {
        failNext?.let { throw it.also { failNext = null } }
        return branches.value.filter { it.patternId == patternId }
    }

    override fun observeBranchesForPattern(patternId: String): Flow<List<ChartBranch>> =
        branches.map { list -> list.filter { it.patternId == patternId } }

    override suspend fun createBranch(branch: ChartBranch): ChartBranch {
        failNext?.let { throw it.also { failNext = null } }
        // Idempotent on (patternId, branchName) — mirrors the production
        // SQLDelight `INSERT OR IGNORE` semantics. CreateBranchUseCase
        // pre-empts duplicates with a Validation error before ever reaching
        // this branch, so the existing-row return path is unreachable from
        // the use case itself; it stays here for future tests that exercise
        // the repository contract directly.
        val existing =
            branches.value.firstOrNull {
                it.patternId == branch.patternId && it.branchName == branch.branchName
            }
        if (existing != null) return existing
        branches.value = branches.value + branch
        return branch
    }

    override suspend fun advanceTip(
        patternId: String,
        branchName: String,
        tipRevisionId: String,
    ) {
        failNext?.let { throw it.also { failNext = null } }
        branches.value =
            branches.value.map { branch ->
                if (branch.patternId == patternId && branch.branchName == branchName) {
                    branch.copy(tipRevisionId = tipRevisionId, updatedAt = Clock.System.now())
                } else {
                    branch
                }
            }
    }

    override suspend fun deleteBranch(branchId: String) {
        failNext?.let { throw it.also { failNext = null } }
        branches.value = branches.value.filter { it.id != branchId }
    }

    fun seed(branch: ChartBranch) {
        branches.value = branches.value + branch
    }
}
