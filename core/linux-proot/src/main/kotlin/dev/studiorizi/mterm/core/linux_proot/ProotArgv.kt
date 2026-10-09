package dev.studiorizi.mterm.core.linux_proot

import java.io.File

/**
 * Shared PRoot argv builder (Termux-family long options, verified against
 * proot 5.1.107.96 `--help` strings).
 *
 * [ProotBackend.prepare] and [GuestProbe] both build from here so the
 * interactive session and one-shot doctor commands can never drift apart.
 */
object ProotArgv {

    const val GUEST_HOME = "/home/user"
    const val GUEST_USER = "user"
    const val BRIDGE_GUEST_PATH = "/run/android-bridge"
    const val MIRROR_GUEST_PATH = "/mnt/shared"

    val DEFAULT_SHELL: List<String> = listOf("/bin/zsh", "-l")

    fun build(
        prootBin: File,
        rootfsDir: File,
        bridgeDir: File,
        mirrorDir: File,
        guestCommand: List<String>,
    ): List<String> {
        val tail = if (guestCommand.isEmpty() || guestCommand == DEFAULT_SHELL) {
            DEFAULT_SHELL
        } else {
            guestCommand
        }
        return listOf(
            prootBin.absolutePath,
            "--rootfs", rootfsDir.absolutePath,
            "--bind", "${bridgeDir.absolutePath}:$BRIDGE_GUEST_PATH",
            "--bind", "${mirrorDir.absolutePath}:$MIRROR_GUEST_PATH",
            "--cwd", GUEST_HOME,
            "/usr/bin/env", "-i",
            "HOME=$GUEST_HOME",
            "USER=$GUEST_USER",
            "TERM=xterm-256color",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "SHELL=/bin/zsh",
        ) + tail
    }

    /** Host env for spawning proot: loader path for the bundled libs only. */
    fun hostEnv(prootBin: File): Array<String> =
        arrayOf(
            "LD_LIBRARY_PATH=${prootBin.absoluteFile.parentFile?.absolutePath ?: "."}",
        )
}
