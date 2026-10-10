package dev.studiorizi.mterm.full.backend

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Receives files shared from other apps (`ACTION_VIEW`) into the isolated
 * inbox at `shared/inbox/`, from where the Debian guest sees them at
 * `/mnt/shared/inbox/`.
 *
 * Inbox only: files are never executed and never leave the app sandbox
 * without an explicit user sync/open. 32MB cap, sanitized names.
 */
object InboxBridge {
    /** Pending shared URIs from `onNewIntent` (consumed by the UI once). */
    val pending = Channel<Uri>(Channel.BUFFERED)

    suspend fun importViewed(context: Context, uri: Uri): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val name = sanitize(displayName(context, uri) ?: "shared-file")
                val inbox = File(context.filesDir, "shared/inbox")
                if (!inbox.isDirectory && !inbox.mkdirs()) {
                    return@withContext Result.failure(
                        IllegalStateException("cannot create inbox"),
                    )
                }
                val out = File(inbox, name)
                var total = 0L
                context.contentResolver.openInputStream(uri)?.buffered().use { input ->
                    if (input == null) {
                        return@withContext Result.failure(
                            IllegalStateException("cannot read shared file"),
                        )
                    }
                    out.outputStream().buffered().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) {
                                runCatching { out.delete() }
                                return@withContext Result.failure(
                                    IllegalStateException("shared file too large (>32MB)"),
                                )
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
            .ifEmpty { "shared-file" }
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('.', '_')
        return cleaned.take(128).ifEmpty { "shared-file" }
    }

    const val MAX_BYTES = 32L * 1024 * 1024
}
