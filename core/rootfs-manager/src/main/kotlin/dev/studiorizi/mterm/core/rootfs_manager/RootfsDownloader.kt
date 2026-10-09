package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resumable rootfs archive downloader (pure JVM, no extra dependencies).
 *
 * Writes to `<dest>.part` and resumes with `Range` when the server honors
 * it (206); restarts from zero on 200/416 or when the partial file is
 * inconsistent. Progress reports downloaded bytes vs [expectedSize]
 * (<= 0 means unknown). All network I/O runs on [Dispatchers.IO] with
 * connect/read timeouts; every failure surfaces as a failed [Result]
 * (never throws).
 */
object RootfsDownloader {

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val BUFFER_SIZE = 64 * 1024

    /**
     * Downloads [url] to [dest] (via `<dest>.part`), then atomically renames
     * to [dest] on success. Returns the final file.
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        userAgent: String = "mterm-rootfs/1",
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            var downloaded = if (part.isFile) part.length() else 0L
            if (expectedSize > 0 && downloaded > expectedSize) {
                part.delete()
                downloaded = 0L
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK &&
                    code != HttpURLConnection.HTTP_PARTIAL
                ) {
                    return@withContext Result.failure(
                        IllegalStateException("download HTTP $code for $url"),
                    )
                }
                if (code == HttpURLConnection.HTTP_OK && downloaded > 0) {
                    // Server ignored Range: restart from zero.
                    part.delete()
                    downloaded = 0L
                }
                val total = if (expectedSize > 0) {
                    expectedSize
                } else {
                    val remaining = connection.contentLengthLong
                    if (remaining > 0) downloaded + remaining else -1L
                }
                onProgress(downloaded, total)
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(downloaded)
                    connection.inputStream.buffered().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (expectedSize > 0 && downloaded != expectedSize) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "size mismatch: got $downloaded bytes, expected $expectedSize",
                        ),
                    )
                }
                if (dest.isFile && !dest.delete()) {
                    return@withContext Result.failure(
                        IllegalStateException("cannot replace $dest"),
                    )
                }
                if (!part.renameTo(dest)) {
                    return@withContext Result.failure(
                        IllegalStateException("cannot promote ${part.name}"),
                    )
                }
                Result.success(dest)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
