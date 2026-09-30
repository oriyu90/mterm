package dev.studiorizi.mterm.core.android_bridge

import java.io.File

/**
 * Validates guest-supplied paths against [allowedRoots].
 *
 * Rejects `..` segments, NUL bytes and anything that canonicalizes
 * outside the allowed roots. Pure JVM; no Android APIs.
 */
class BridgePathPolicy(private val allowedRoots: List<File>) {

    fun checkGuestPath(guest: String): Result<File> {
        if (guest.isEmpty()) return Result.failure(IllegalArgumentException("empty path"))
        if (guest.contains('\u0000')) return Result.failure(IllegalArgumentException("NUL byte in path"))
        if (guest.split('/').any { it == ".." }) {
            return Result.failure(SecurityException("path traversal rejected: $guest"))
        }
        if (allowedRoots.isEmpty()) return Result.failure(IllegalStateException("no allowed roots"))
        val candidate = if (guest.startsWith("/")) File(guest) else File(allowedRoots.first(), guest)
        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val inside = allowedRoots.any { root ->
            val rootPath = root.canonicalFile.path
            canonical.path == rootPath || canonical.path.startsWith("$rootPath/")
        }
        return if (inside) {
            Result.success(canonical)
        } else {
            Result.failure(SecurityException("path outside allowed roots: $guest"))
        }
    }
}
