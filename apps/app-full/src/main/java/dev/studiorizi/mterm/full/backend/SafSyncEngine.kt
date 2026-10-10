package dev.studiorizi.mterm.full.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.studiorizi.mterm.core.storage_mirror.ConflictDecision
import dev.studiorizi.mterm.core.storage_mirror.ConflictInfo
import dev.studiorizi.mterm.core.storage_mirror.StorageMirrorManager
import dev.studiorizi.mterm.core.storage_mirror.SyncExcludes
import dev.studiorizi.mterm.core.storage_mirror.SyncJournalEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real SAF <-> mirror sync engine (platform layer).
 *
 * Direction model: the SAF tree is the Android side, `shared/<mountId>/mirror/`
 * is the Linux side (bound into the guest at `/mnt/shared`). Import copies
 * SAF -> mirror; conflict resolution copies per decision. Every applied copy
 * is journaled via [StorageMirrorManager]. Pure path policy stays in
 * [StorageMirrorManager] / [SyncExcludes]; this object only performs I/O.
 *
 * Safety: traversal/NUL rejected by the manager, excludes honored, per-file
 * 32MB cap, 1000-file cap per run (remainder on next run), no hardlinks
 * (app-private link(2) is EPERM), no symlinks followed.
 */
object SafSyncEngine {

    const val MAX_FILES = 1000
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    data class SyncStats(
        val copied: Int,
        val skippedUpToDate: Int,
        val skippedExcluded: Int,
        val skippedTooLarge: Int,
        val bytes: Long,
        val truncated: Boolean,
        val conflicts: List<ConflictInfo>,
    )

    /**
     * Stable mount id from a SAF tree URI (`primary:Shared` -> `primary-Shared`).
     * Falls back to `shared` when the id is missing or fully unsanitary.
     */
    fun mountIdFor(treeUri: Uri): String {
        val raw = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            ""
        }.ifEmpty { treeUri.lastPathSegment ?: "" }
            // Bare ".../tree/" has no document id; "tree" is the provider
            // marker, not a mount name.
            .takeUnless { it == "tree" } ?: ""
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-'
        }.joinToString("").trim('-', '_')
        return cleaned.take(48).ifEmpty { "shared" }
    }

    private fun lastSync(manager: StorageMirrorManager, mountId: String): Long =
        manager.listJournal(mountId).maxOfOrNull { it.lastSyncedVersion } ?: 0L

    suspend fun importTree(
        context: Context,
        treeUri: Uri,
        manager: StorageMirrorManager,
        mountId: String,
        onProgress: (filesDone: Int, bytes: Long) -> Unit = { _, _ -> },
    ): Result<SyncStats> = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext Result.failure(IllegalStateException("cannot open SAF tree"))
            if (!root.isDirectory) {
                return@withContext Result.failure(IllegalStateException("SAF tree is not a directory"))
            }
            val lastSync = lastSync(manager, mountId)
            var copied = 0
            var upToDate = 0
            var excluded = 0
            var tooLarge = 0
            var bytes = 0L
            var files = 0
            var truncated = false
            val conflicts = mutableListOf<ConflictInfo>()
            val stack = ArrayDeque<Pair<DocumentFile, String>>()
            stack.add(root to "")
            while (stack.isNotEmpty()) {
                if (files >= MAX_FILES) {
                    truncated = true
                    break
                }
                val (dir, rel) = stack.removeLast()
                val children = try {
                    dir.listFiles().toList()
                } catch (_: Exception) {
                    continue
                }
                for (child in children) {
                    if (files >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    val name = child.name ?: continue
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (SyncExcludes.isExcluded(childRel)) {
                        excluded++
                        continue
                    }
                    if (child.isDirectory) {
                        stack.add(child to childRel)
                        continue
                    }
                    if (!child.isFile) continue
                    val size = child.length()
                    if (size < 0 || size > MAX_FILE_BYTES) {
                        tooLarge++
                        continue
                    }
                    val target = manager.mapGuestToMirror(mountId, childRel).getOrElse { continue }
                    val safTime = child.lastModified()
                    if (target.isFile && target.length() == size && target.lastModified() == safTime) {
                        upToDate++
                        continue
                    }
                    if (target.isFile && target.lastModified() > lastSync &&
                        safTime > lastSync && target.lastModified() != safTime
                    ) {
                        conflicts.add(ConflictInfo(childRel, safTime, target.lastModified()))
                        continue
                    }
                    copySafToMirror(context, child, target, size)?.let { return@withContext Result.failure(it) }
                    target.setLastModified(safTime)
                    manager.recordJournal(
                        mountId,
                        SyncJournalEntry(childRel, size, safTime, null, "saf-to-mirror", System.currentTimeMillis()),
                    )
                    copied++
                    files++
                    bytes += size
                    onProgress(files, bytes)
                }
            }
            Result.success(SyncStats(copied, upToDate, excluded, tooLarge, bytes, truncated, conflicts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolveConflict(
        context: Context,
        treeUri: Uri,
        manager: StorageMirrorManager,
        mountId: String,
        info: ConflictInfo,
        decision: ConflictDecision,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            when (decision) {
                ConflictDecision.KEEP_ANDROID -> {
                    val root = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext Result.failure(IllegalStateException("cannot open SAF tree"))
                    val doc = findDoc(root, info.path)
                        ?: return@withContext Result.failure(IllegalStateException("SAF file gone: ${info.path}"))
                    val target = manager.mapGuestToMirror(mountId, info.path).getOrElse {
                        return@withContext Result.failure(it)
                    }
                    copySafToMirror(context, doc, target, doc.length())?.let {
                        return@withContext Result.failure(it)
                    }
                    target.setLastModified(doc.lastModified())
                    manager.recordJournal(
                        mountId,
                        SyncJournalEntry(info.path, doc.length(), doc.lastModified(), null, "saf-to-mirror", now),
                    )
                }
                ConflictDecision.KEEP_LINUX -> {
                    val root = DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext Result.failure(IllegalStateException("cannot open SAF tree"))
                    val source = manager.mapGuestToMirror(mountId, info.path).getOrElse {
                        return@withContext Result.failure(it)
                    }
                    if (!source.isFile) {
                        return@withContext Result.failure(IllegalStateException("mirror file gone: ${info.path}"))
                    }
                    copyMirrorToSaf(context, root, info.path, source)?.let {
                        return@withContext Result.failure(it)
                    }
                    manager.recordJournal(
                        mountId,
                        SyncJournalEntry(info.path, source.length(), source.lastModified(), null, "mirror-to-saf", now),
                    )
                }
                ConflictDecision.DUPLICATE -> {
                    val source = manager.mapGuestToMirror(mountId, info.path).getOrElse {
                        return@withContext Result.failure(it)
                    }
                    if (!source.isFile) {
                        return@withContext Result.failure(IllegalStateException("mirror file gone: ${info.path}"))
                    }
                    val dup = manager.mapGuestToMirror(mountId, info.path + ".linux").getOrElse {
                        return@withContext Result.failure(it)
                    }
                    dup.parentFile?.mkdirs()
                    source.copyTo(dup, overwrite = true)
                    manager.recordJournal(
                        mountId,
                        SyncJournalEntry(info.path + ".linux", dup.length(), dup.lastModified(), null, "duplicate", now),
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copySafToMirror(context: Context, doc: DocumentFile, target: File, size: Long): Throwable? {
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            context.contentResolver.openInputStream(doc.uri)?.buffered().use { input ->
                if (input == null) return IllegalStateException("cannot read SAF file: ${doc.uri}")
                tmp.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            if (tmp.length() != size && size >= 0) {
                tmp.delete()
                return IllegalStateException("size changed during copy: ${doc.uri}")
            }
            if (target.isFile && !target.delete()) {
                tmp.delete()
                return IllegalStateException("cannot replace $target")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return IllegalStateException("cannot promote ${target.name}")
            }
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun copyMirrorToSaf(
        context: Context,
        root: DocumentFile,
        rel: String,
        source: File,
    ): Throwable? {
        return try {
            if (source.length() > MAX_FILE_BYTES) {
                return IllegalStateException("too large to write back: $rel")
            }
            val segments = rel.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            if (segments.isEmpty()) return IllegalStateException("empty path")
            var dir = root
            for (segment in segments.dropLast(1)) {
                val existing = dir.findFile(segment)
                dir = when {
                    existing?.isDirectory == true -> existing
                    existing != null -> return IllegalStateException("not a directory in SAF tree: $segment")
                    else -> dir.createDirectory(segment)
                        ?: return IllegalStateException("cannot create SAF dir: $segment")
                }
            }
            val name = segments.last()
            dir.findFile(name)?.delete()
            val doc = dir.createFile("application/octet-stream", name)
                ?: return IllegalStateException("cannot create SAF file: $name")
            context.contentResolver.openOutputStream(doc.uri)?.buffered().use { output ->
                if (output == null) return IllegalStateException("cannot write SAF file: $name")
                source.inputStream().buffered().use { input ->
                    input.copyTo(output)
                }
            }
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun findDoc(root: DocumentFile, rel: String): DocumentFile? {
        var current = root
        for (segment in rel.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") return null
            current = current.findFile(segment) ?: return null
        }
        return current.takeIf { it.isFile }
    }
}
