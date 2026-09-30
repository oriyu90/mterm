package dev.studiorizi.mterm.core.storage_mirror

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages the app-private mirror of a SAF tree.
 *
 * Layout under [mirrorRoot]: `<mountId>/mirror/<guestRel>` plus
 * `<mountId>/sync.journal.jsonl`.
 *
 * Pure JVM (java.io only); SAF DocumentFile I/O lives in the caller.
 */
class StorageMirrorManager(private val mirrorRoot: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun journalFile(mountId: String): File {
        requireValidMountId(mountId)
        return File(File(mirrorRoot, mountId), JOURNAL_NAME)
    }

    /** Maps a guest-relative path to its mirror file, rejecting traversal. */
    fun mapGuestToMirror(mountId: String, guestRel: String): Result<File> {
        if (guestRel.isEmpty()) return Result.failure(IllegalArgumentException("empty path"))
        if (guestRel.contains('\u0000')) return Result.failure(IllegalArgumentException("NUL byte in path"))
        if (guestRel.startsWith("/")) return Result.failure(IllegalArgumentException("absolute path not allowed"))
        if (guestRel.split('/').any { it == ".." }) {
            return Result.failure(IllegalArgumentException("path traversal rejected: $guestRel"))
        }
        val mountError = runCatching { requireValidMountId(mountId) }.exceptionOrNull()
        if (mountError != null) return Result.failure(mountError)
        return Result.success(File(File(File(mirrorRoot, mountId), MIRROR_DIR), guestRel))
    }

    /**
     * Returns a [ConflictInfo] when both sides changed after [lastSync]
     * and disagree, otherwise null.
     */
    fun detectConflict(
        androidMtime: Long,
        linuxMtime: Long,
        lastSync: Long,
        path: String = "",
    ): ConflictInfo? {
        if (androidMtime > lastSync && linuxMtime > lastSync && androidMtime != linuxMtime) {
            return ConflictInfo(path, androidMtime, linuxMtime)
        }
        return null
    }

    /** Appends one JSON line to the mount journal, creating directories. */
    fun recordJournal(mountId: String, entry: SyncJournalEntry) {
        val file = journalFile(mountId)
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(SyncJournalEntry.serializer(), entry) + "\n")
    }

    fun listJournal(mountId: String): List<SyncJournalEntry> {
        val file = journalFile(mountId)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                json.decodeFromString(SyncJournalEntry.serializer(), trimmed)
            }
        }
    }

    private fun requireValidMountId(mountId: String) {
        require(mountId.isNotEmpty()) { "empty mountId" }
        require(!mountId.contains('\u0000')) { "NUL byte in mountId" }
        require(!mountId.contains('/') && !mountId.contains('\\')) { "mountId must not contain separators" }
        require(mountId != "." && mountId != "..") { "invalid mountId: $mountId" }
    }

    companion object {
        const val MIRROR_DIR = "mirror"
        const val JOURNAL_NAME = "sync.journal.jsonl"
    }
}
