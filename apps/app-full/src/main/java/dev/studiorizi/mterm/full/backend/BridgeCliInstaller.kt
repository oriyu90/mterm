package dev.studiorizi.mterm.full.backend

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs the guest bridge CLI (`bridge-cli.sh` + wrappers) from APK assets
 * into the Debian guest at `/usr/local/bin`.
 *
 * Pure JVM: script contents are supplied by the caller (app layer reads
 * `assets/bridge-cli/`), so this stays unit-testable without Android.
 * Idempotent: rewrites only when content differs. Never throws.
 */
object BridgeCliInstaller {

    const val GUEST_BIN = "usr/local/bin"

    val SCRIPT_NAMES: List<String> = listOf(
        "bridge-cli.sh",
        "notify.sh",
        "open.sh",
        "pbcopy.sh",
        "pbpaste.sh",
    )

    suspend fun ensure(rootfsDir: File, scripts: Map<String, String>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val binDir = File(rootfsDir, GUEST_BIN)
                if (!binDir.isDirectory && !binDir.mkdirs()) {
                    return@withContext Result.failure(
                        IllegalStateException("cannot create $binDir"),
                    )
                }
                var updated = 0
                for (name in SCRIPT_NAMES) {
                    val content = scripts[name]
                        ?: return@withContext Result.failure(
                            IllegalStateException("missing bundled script: $name"),
                        )
                    val out = File(binDir, name)
                    if (out.isFile && out.readText(Charsets.UTF_8) == content) {
                        continue
                    }
                    out.writeText(content, Charsets.UTF_8)
                    if (!out.setExecutable(true)) {
                        return@withContext Result.failure(
                            IllegalStateException("cannot chmod +x: $out"),
                        )
                    }
                    updated++
                }
                Result.success(updated)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
