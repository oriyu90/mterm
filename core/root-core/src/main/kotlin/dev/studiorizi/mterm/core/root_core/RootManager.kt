package dev.studiorizi.mterm.core.root_core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root capability diagnostics (plan section 13).
 *
 * No UI calls here and no automatic su prompts: [RootManager.detectSu]
 * only probes with a no-op command and must be invoked from an explicit
 * user action (e.g. the diagnostics screen).
 *
 * Mount-leak prevention: the chroot backend is enabled only when a private
 * mount namespace is available ([isPrivateNamespaceSupported]). Terminals
 * that cannot create one report the root chroot backend as unsupported
 * instead of performing global mounts.
 */
data class RootCapabilities(
    val suAvailable: Boolean,
    val mountNamespace: Boolean,
    val bindMount: Boolean,
    val chroot: Boolean,
) {
    fun allOk(): Boolean = suAvailable && mountNamespace && bindMount && chroot
}

class RootManager {

    /**
     * Probes for root with a no-op ("su -c exit"); never initiates a
     * root request on its own. Returns false on timeout, non-zero exit,
     * or any error. Call only from explicit user action.
     */
    fun detectSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "exit"))
            val finished = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks for a private mount-namespace capability: the mntns entry
     * exists and an "unshare -m true" dry-run succeeds.
     */
    fun checkMountNamespace(): Boolean {
        if (!File("/proc/self/ns/mnt").exists()) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("unshare", "-m", "true"))
            val finished = process.waitFor(UNSHARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    fun capabilities(): RootCapabilities {
        return RootCapabilities(
            suAvailable = detectSu(),
            mountNamespace = checkMountNamespace(),
            bindMount = hasExecutable("mount"),
            chroot = hasExecutable("chroot"),
        )
    }

    companion object {
        const val SU_TIMEOUT_SECONDS = 2L
        const val UNSHARE_TIMEOUT_SECONDS = 2L

        /** Static check usable without a [RootManager] instance. */
        fun isPrivateNamespaceSupported(): Boolean {
            return File("/proc/self/ns/mnt").exists() && hasExecutable("unshare")
        }

        private fun hasExecutable(name: String): Boolean {
            for (dir in SYSTEM_BIN_DIRS) {
                val file = File(dir, name)
                if (file.isFile && file.canExecute()) return true
            }
            return false
        }

        private val SYSTEM_BIN_DIRS = listOf(
            "/system/bin",
            "/system/xbin",
            "/vendor/bin",
            "/odm/bin",
        )
    }
}
