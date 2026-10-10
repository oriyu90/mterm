package dev.studiorizi.mterm.core.linux_proot

import java.io.File

/**
 * Shared PRoot argv builder (Termux-family long options, verified against
 * proot 5.1.107.96 `--help` strings AND on-device execution).
 *
 * Syntax ground truth: long options take `=`-joined values
 * (`--rootfs=<dir>`); the space-separated form is rejected at runtime
 * ("option '--rootfs' and its value must be separated by '='").
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

    /**
     * Host system paths bound 1:1 into the guest. The tarball cannot carry
     * device nodes (unprivileged installs cannot mknod), so the guest gets
     * working /dev/null|zero|random|urandom, /proc and /sys from the host
     * (same practice as proot-distro). These paths always exist on Android.
     */
    val SYSTEM_BINDS: List<String> = listOf("/dev", "/proc", "/sys")

    fun build(
        prootBin: File,
        rootfsDir: File,
        bridgeDir: File,
        mirrorDir: File,
        guestCommand: List<String>,
        extraGuestEnv: List<String> = emptyList(),
    ): List<String> {
        val tail = if (guestCommand.isEmpty() || guestCommand == DEFAULT_SHELL) {
            DEFAULT_SHELL
        } else {
            guestCommand
        }
        // The whole shared tree is visible in-guest: flat mirror/,
        // inbox/, and per-mount <mountId>/mirror/ subtrees. Binding the
        // tree (not just mirror/) keeps SAF mounts and the inbox reachable
        // without per-mount bind bookkeeping.
        val sharedRoot = mirrorDir.parentFile ?: mirrorDir
        val binds = listOf(
            "--bind=${bridgeDir.absolutePath}:$BRIDGE_GUEST_PATH",
            "--bind=${sharedRoot.absolutePath}:$MIRROR_GUEST_PATH",
        ) + SYSTEM_BINDS.map { "--bind=$it" }
        return listOf(
            prootBin.absolutePath,
            "--rootfs=${rootfsDir.absolutePath}",
            // -0 (fake root): guest uid/gid appear as 0 so dpkg/apt and
            // installers work without Android root. Files stay owned by the
            // app UID on the host; the Android sandbox still applies.
            "-0",
        ) + binds + listOf(
            "--cwd=$GUEST_HOME",
            "/usr/bin/env", "-i",
            "HOME=$GUEST_HOME",
            "USER=$GUEST_USER",
            "TERM=xterm-256color",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "SHELL=/bin/zsh",
            // libuv (Node.js and anything built on it) tries io_uring,
            // which hangs under proot ptrace; force the epoll fallback
            // for every guest process (verified on device).
            "UV_USE_IO_URING=0",
        ) + extraGuestEnv + tail
    }

    /** Host env for spawning proot: loader path for the bundled libs, a
     * writable temp dir (Termux proot defaults to its own prefix TMPDIR),
     * and the exec-helper loaders (Termux build expects them via env;
     * without them every guest exec fails with ENOENT). */
    fun hostEnv(prootBin: File, tmpDir: File): Array<String> {
        val dir = prootBin.absoluteFile.parentFile?.absolutePath ?: "."
        return arrayOf(
            "LD_LIBRARY_PATH=$dir",
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
            "PROOT_LOADER=$dir/${ProotInstaller.LOADER_NAME}",
            "PROOT_LOADER_32=$dir/${ProotInstaller.LOADER32_NAME}",
        )
    }

    /** Extracts the `--rootfs=` value from a built argv, or null. */
    fun rootfsFromArgv(argv: List<String>): File? =
        argv.firstOrNull { it.startsWith("--rootfs=") }
            ?.removePrefix("--rootfs=")
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
}
