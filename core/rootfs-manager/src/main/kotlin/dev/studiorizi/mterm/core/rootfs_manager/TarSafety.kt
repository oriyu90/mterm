package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File

/**
 * Tar extraction guards (plan section 14.2): reject absolute paths,
 * ".." traversal and empty names before writing anything.
 * Symlink escape is a post-extract check (a symlink may point outside
 * [base] even when its own path resolves under it) and is NOT covered here.
 */
object TarSafety {

    fun isSafeEntry(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("/")) return false
        for (segment in name.split('/')) {
            if (segment == "..") return false
        }
        return true
    }

    /**
     * Resolves [name] under [base] and fails unless the normalised path
     * stays inside [base].
     */
    fun resolveUnder(base: File, name: String): Result<File> {
        if (!isSafeEntry(name)) {
            return Result.failure(IllegalArgumentException("unsafe tar entry: $name"))
        }
        val basePath = base.toPath().toAbsolutePath().normalize()
        val resolved = basePath.resolve(name).normalize()
        if (!resolved.startsWith(basePath)) {
            return Result.failure(IllegalArgumentException("tar entry escapes base: $name"))
        }
        return Result.success(resolved.toFile())
    }
}
