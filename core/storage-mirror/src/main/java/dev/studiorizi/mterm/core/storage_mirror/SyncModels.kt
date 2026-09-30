package dev.studiorizi.mterm.core.storage_mirror

import kotlinx.serialization.Serializable

/** One line of the per-mount sync journal (stored as JSON lines). */
@Serializable
data class SyncJournalEntry(
    val relativePath: String,
    val size: Long,
    val mtime: Long,
    val sha256: String? = null,
    val direction: String,
    val lastSyncedVersion: Long,
)

enum class ConflictDecision {
    KEEP_ANDROID,
    KEEP_LINUX,
    DUPLICATE,
}

data class ConflictInfo(
    val path: String,
    val androidMtime: Long,
    val linuxMtime: Long,
)
