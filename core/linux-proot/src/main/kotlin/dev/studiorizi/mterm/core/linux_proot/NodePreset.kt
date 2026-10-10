package dev.studiorizi.mterm.core.linux_proot

import dev.studiorizi.mterm.core.rootfs_manager.RootfsDownloader
import dev.studiorizi.mterm.core.rootfs_manager.TarExtractor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Node.js LTS preset: official arm64 binary tarball (no apt/dpkg involved,
 * so the app-private hardlink restriction never bites).
 *
 * Layout under the guest home: `.local/node/{bin,lib,...}` plus a
 * `.profile` snippet prepending `.local/node/bin` to PATH (idempotent —
 * interactive login shells pick it up; one-shot probes pass PATH
 * explicitly).
 */
object NodePreset {

    const val VERSION = "24.21.0"
    const val DIR_NAME = "node-v24.21.0-linux-arm64"

    fun url(): String = "https://nodejs.org/dist/v$VERSION/$DIR_NAME.tar.xz"

    fun nodeDir(homeDir: File): File = File(homeDir, ".local/node")

    fun profileFile(homeDir: File): File = File(homeDir, ".profile")

    // Note: plain val (not const) because the snippet contains shell `$`.
    val PROFILE_SNIPPET = """
# mterm node preset (managed): prefer the bundled Node LTS.
case ":${'$'}PATH:" in
*":${'$'}HOME/.local/node/bin:"*) ;;
*) export PATH="${'$'}HOME/.local/node/bin:${'$'}PATH" ;;
esac
# libuv io_uring hangs under proot ptrace; keep the epoll fallback.
export UV_USE_IO_URING=0
"""

    data class Installed(val nodeDir: File, val version: String)

    suspend fun install(
        filesDir: File,
        homeDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Installed> = withContext(Dispatchers.IO) {
        try {
            val cache = File(filesDir, "linux/cache/node-$VERSION.tar.xz")
            if (!cache.isFile) {
                RootfsDownloader.download(url(), cache, -1L, onProgress = onProgress)
                    .getOrElse { return@withContext Result.failure(it) }
            }
            val target = nodeDir(homeDir)
            if (target.isDirectory) target.deleteRecursively()
            target.mkdirs()
            TarExtractor.extract(cache, target, stripComponents = 1)
                .getOrElse { return@withContext Result.failure(it) }
            ensureProfileSnippet(homeDir)
            val nodeBin = File(target, "bin/node")
            if (!nodeBin.isFile) {
                return@withContext Result.failure(
                    IllegalStateException("node binary missing after extract"),
                )
            }
            nodeBin.setExecutable(true)
            Result.success(Installed(target, VERSION))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Appends the PATH snippet to ~/.profile once (never duplicates). */
    fun ensureProfileSnippet(homeDir: File) {
        val profile = profileFile(homeDir)
        profile.parentFile?.mkdirs()
        val current = if (profile.isFile) profile.readText(Charsets.UTF_8) else ""
        if (!current.contains("mterm node preset (managed)")) {
            profile.appendText(
                (if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else "") +
                    PROFILE_SNIPPET.trim() + "\n",
                Charsets.UTF_8,
            )
        }
    }
}
