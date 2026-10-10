package dev.studiorizi.mterm.core.linux_core

import java.io.File

/**
 * Maps guest (Debian) absolute paths to host files under [rootfsDir].
 *
 * Safety: every lookup normalizes `.` / `..` without touching the filesystem
 * and rejects escapes above the root, null bytes, and non-absolute guests.
 * The bridge socket dir is visible in-guest at [BRIDGE_GUEST_PREFIX] and the
 * shared mirror at [MIRROR_GUEST_PREFIX] (see ProotArgv binds).
 */
class RootfsPathMapper(
    private val rootfsDir: File,
    private val bridgeDir: File,
    private val mirrorDir: File = File(bridgeDir.parentFile, "mirror"),
    private val sharedDir: File = mirrorDir.parentFile ?: mirrorDir,
) {
    companion object {
        const val BRIDGE_GUEST_PREFIX = "/run/android-bridge"
        const val MIRROR_GUEST_PREFIX = "/mnt/shared"
    }

    fun guestToHost(guest: String): Result<File> {
        if (guest.isEmpty()) {
            return Result.failure(IllegalArgumentException("Guest path must not be empty"))
        }
        if ('\u0000' in guest) {
            return Result.failure(IllegalArgumentException("Guest path contains null byte"))
        }
        if (!guest.startsWith('/')) {
            return Result.failure(IllegalArgumentException("Guest path must be absolute: $guest"))
        }
        val normalized = normalize(guest)
            ?: return Result.failure(SecurityException("Path escapes rootfs: $guest"))
        val host = if (normalized.isEmpty()) rootfsDir else File(rootfsDir, normalized)
        // Defense in depth: resolved file must stay under the root.
        val rootAbs = rootfsDir.absolutePath
        val hostAbs = host.absolutePath
        if (hostAbs != rootAbs && !hostAbs.startsWith(rootAbs + File.separatorChar)) {
            return Result.failure(SecurityException("Path escapes rootfs: $guest"))
        }
        return Result.success(host)
    }

    fun hostToGuest(host: File): String? {
        val abs = host.absolutePath
        val rootAbs = rootfsDir.absolutePath
        if (abs == rootAbs) return "/"
        if (abs.startsWith(rootAbs + File.separatorChar)) {
            return "/" + abs.substring(rootAbs.length + 1).replace(File.separatorChar, '/')
        }
        val bridgeAbs = bridgeDir.absolutePath
        if (abs == bridgeAbs) return BRIDGE_GUEST_PREFIX
        if (abs.startsWith(bridgeAbs + File.separatorChar)) {
            return BRIDGE_GUEST_PREFIX + "/" +
                abs.substring(bridgeAbs.length + 1).replace(File.separatorChar, '/')
        }
        val mirrorAbs = mirrorDir.absolutePath
        if (abs == mirrorAbs) return MIRROR_GUEST_PREFIX
        if (abs.startsWith(mirrorAbs + File.separatorChar)) {
            return MIRROR_GUEST_PREFIX + "/" +
                abs.substring(mirrorAbs.length + 1).replace(File.separatorChar, '/')
        }
        val sharedAbs = sharedDir.absolutePath
        if (abs == sharedAbs) return MIRROR_GUEST_PREFIX
        if (abs.startsWith(sharedAbs + File.separatorChar)) {
            return MIRROR_GUEST_PREFIX + "/" +
                abs.substring(sharedAbs.length + 1).replace(File.separatorChar, '/')
        }
        return null
    }

    /**
     * Maps a guest shared path (`/mnt/shared/...`) to its host file under
     * the shared tree. The bridge subtree is never exposed (sockets and
     * ephemeral helpers live there); traversal, NUL and non-absolute
     * inputs fail closed.
     */
    fun sharedToHost(guest: String): Result<File> {
        if (guest.isEmpty() || '\u0000' in guest) {
            return Result.failure(IllegalArgumentException("bad guest path"))
        }
        val prefix = "$MIRROR_GUEST_PREFIX/"
        if (guest != MIRROR_GUEST_PREFIX && !guest.startsWith(prefix)) {
            return Result.failure(SecurityException("only $MIRROR_GUEST_PREFIX is shared: $guest"))
        }
        val rel = guest.removePrefix(MIRROR_GUEST_PREFIX).trimStart('/')
        val segments = rel.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            return Result.failure(SecurityException("path traversal rejected: $guest"))
        }
        if (segments.firstOrNull() == "bridge") {
            return Result.failure(SecurityException("bridge subtree is not shared: $guest"))
        }
        val host = if (segments.isEmpty()) sharedDir else File(sharedDir, segments.joinToString("/"))
        val sharedAbs = sharedDir.absolutePath
        val hostAbs = host.absolutePath
        if (hostAbs != sharedAbs && !hostAbs.startsWith(sharedAbs + File.separatorChar)) {
            return Result.failure(SecurityException("path escapes shared tree: $guest"))
        }
        return Result.success(host)
    }

    /**
     * Normalizes an absolute guest path to a root-relative string,
     * or null when `..` would escape the root.
     */
    private fun normalize(guest: String): String? {
        val parts = ArrayDeque<String>()
        for (segment in guest.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString(File.separator)
    }
}
