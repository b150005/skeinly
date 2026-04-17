package io.github.b150005.knitnote.data.sync

enum class SyncEntityType(
    val value: String,
) {
    PROJECT("project"),
    PROGRESS("progress"),
    PATTERN("pattern"),
    ;

    companion object {
        fun fromValue(value: String): SyncEntityType =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown SyncEntityType: '$value'")
    }
}

enum class SyncOperation(
    val value: String,
) {
    INSERT("insert"),
    UPDATE("update"),
    DELETE("delete"),
    ;

    companion object {
        fun fromValue(value: String): SyncOperation =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown SyncOperation: '$value'")
    }
}

enum class SyncStatus(
    val value: String,
) {
    PENDING("pending"),
    FAILED("failed"),
    ;

    companion object {
        fun fromValue(value: String): SyncStatus =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown SyncStatus: '$value'")
    }
}

data class PendingSyncEntry(
    val id: Long,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperation,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int,
    val status: SyncStatus,
)
