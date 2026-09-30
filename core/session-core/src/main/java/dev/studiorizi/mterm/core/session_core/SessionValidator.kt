package dev.studiorizi.mterm.core.session_core

/**
 * Rejects malformed [SessionSpec]s before they reach a backend.
 *
 * Guards: empty id/title/command, env keys containing '=' or empty,
 * null bytes anywhere, and `..` segments in cwd (directory traversal).
 */
object SessionValidator {
    fun validate(spec: SessionSpec): Result<Unit> {
        if (spec.id.isBlank()) {
            return Result.failure(IllegalArgumentException("Session id must not be empty"))
        }
        if ('\u0000' in spec.id) {
            return Result.failure(IllegalArgumentException("Session id contains null byte"))
        }
        if (spec.title.isBlank()) {
            return Result.failure(IllegalArgumentException("Session title must not be empty"))
        }
        if ('\u0000' in spec.title) {
            return Result.failure(IllegalArgumentException("Session title contains null byte"))
        }
        if (spec.command.isEmpty()) {
            return Result.failure(IllegalArgumentException("Session command must not be empty"))
        }
        for (arg in spec.command) {
            if ('\u0000' in arg) {
                return Result.failure(IllegalArgumentException("Session command contains null byte"))
            }
        }
        for ((key, value) in spec.env) {
            if (key.isEmpty() || '=' in key) {
                return Result.failure(IllegalArgumentException("Invalid env key: '$key'"))
            }
            if ('\u0000' in key) {
                return Result.failure(IllegalArgumentException("Env key contains null byte"))
            }
            if ('\u0000' in value) {
                return Result.failure(IllegalArgumentException("Env value for '$key' contains null byte"))
            }
        }
        val cwd = spec.cwd
        if (cwd != null) {
            if ('\u0000' in cwd) {
                return Result.failure(IllegalArgumentException("Session cwd contains null byte"))
            }
            if (".." in cwd.split('/')) {
                return Result.failure(
                    IllegalArgumentException("Session cwd must not contain '..': '$cwd'"),
                )
            }
        }
        return Result.success(Unit)
    }
}
