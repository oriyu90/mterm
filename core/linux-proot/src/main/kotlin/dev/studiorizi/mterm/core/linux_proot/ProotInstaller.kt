package dev.studiorizi.mterm.core.linux_proot

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the version-pinned PRoot binaries from APK assets into the
 * app-private bin dir and probes them.
 *
 * Pure JVM: asset bytes are supplied by the caller (app layer reads
 * `assets/bin/`), so this stays unit-testable without Android. The loader
 * libs (libtalloc, libandroid-shmem) must sit next to `proot`; callers pass
 * the bin dir as `LD_LIBRARY_PATH` when spawning (see [ProotBackend]).
 */
object ProotInstaller {

    const val PROOT_NAME = "proot"
    const val LOADER_NAME = "loader"
    const val LOADER32_NAME = "loader32"
    const val LIB_TALLOC = "libtalloc.so.2"
    const val LIB_SHMEM = "libandroid-shmem.so"

    /** Asset file names under `assets/bin/` (renamed at build time). */
    val ASSET_NAMES: List<String> = listOf(
        PROOT_NAME,
        LOADER_NAME,
        LOADER32_NAME,
        LIB_TALLOC,
        LIB_SHMEM,
    )

    /**
     * Writes [assets] (name -> bytes) into [binDir], marks them executable,
     * and returns the installed proot [File]. Fails when any entry is missing
     * or when the written bytes differ (verified by re-read).
     */
    suspend fun install(binDir: File, assets: Map<String, ByteArray>): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                if (!binDir.isDirectory && !binDir.mkdirs()) {
                    return@withContext Result.failure(
                        IllegalStateException("cannot create bin dir: $binDir"),
                    )
                }
                for (name in ASSET_NAMES) {
                    val bytes = assets[name]
                        ?: return@withContext Result.failure(
                            IllegalStateException("missing bundled binary: $name"),
                        )
                    val out = File(binDir, name)
                    out.writeBytes(bytes)
                    if (!out.setExecutable(true)) {
                        return@withContext Result.failure(
                            IllegalStateException("cannot chmod +x: $out"),
                        )
                    }
                    val reread = out.readBytes()
                    if (!reread.contentEquals(bytes)) {
                        return@withContext Result.failure(
                            IllegalStateException("written bytes differ: $out"),
                        )
                    }
                }
                Result.success(File(binDir, PROOT_NAME))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Runs `proot --version` with [libDir] on `LD_LIBRARY_PATH` and returns
     * the first output line, or null when the binary cannot start. Never
     * throws; a short timeout keeps a wedged exec from hanging the UI.
     */
    suspend fun probeVersion(prootBin: File, libDir: File): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!prootBin.isFile || !prootBin.canExecute()) return@withContext null
                val process = ProcessBuilder(prootBin.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
                    }
                    .start()
                val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@withContext null
                }
                val line = process.inputStream.bufferedReader().readLine()?.trim()
                if (process.exitValue() == 0 && !line.isNullOrEmpty()) line else null
            } catch (e: Exception) {
                null
            }
        }

    private const val PROBE_TIMEOUT_SECONDS = 10L
}
