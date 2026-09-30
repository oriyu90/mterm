package dev.studiorizi.mterm.core.linux_core

import java.io.File

/**
 * Maps guest (Debian) absolute paths to host files under [rootfsDir].
 *
 * Safety: every lookup normalizes `.` / `..` without touching the filesystem
 * and rejects escapes above the root, null bytes, and non-absolute guests.
 * The shared bridge directory is visible in-guest at [BRIDGE_GUEST_PREFIX].
 */
class RootfsPathMapper(
    private val rootfsDir: File,
    private val bridgeDir: File,
) {
    companion object {
        const val BRIDGE_GUEST_PREFIX = "/mnt/shared"
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
        return null
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
