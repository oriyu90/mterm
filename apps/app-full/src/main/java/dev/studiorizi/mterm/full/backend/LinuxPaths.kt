package dev.studiorizi.mterm.full.backend

import dev.studiorizi.mterm.core.rootfs_manager.RootfsManager
import java.io.File

/**
 * Single source of truth for on-device Linux paths, shared by
 * [dev.studiorizi.mterm.full.service.TerminalService] (sessions) and the
 * Linux setup / doctor UI (installer + one-shot probes). Paths must never
 * be duplicated as literals elsewhere.
 */
object LinuxPaths {
    fun binDir(filesDir: File): File = File(filesDir, "bin")

    fun prootBin(filesDir: File): File = File(binDir(filesDir), "proot")

    fun rootfsDir(filesDir: File): File = RootfsManager.activeRootfsDir(filesDir)

    fun bridgeDir(filesDir: File): File = File(filesDir, "shared/bridge")

    fun mirrorDir(filesDir: File): File = File(filesDir, "shared/mirror")

    fun tmpDir(filesDir: File): File = File(filesDir, "tmp")
}
