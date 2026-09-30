package dev.studiorizi.mterm.core.storage_mirror

/** Default exclusion patterns for the SAF <-> mirror sync engine. */
object SyncExcludes {
    val DEFAULT = listOf("node_modules/", ".git/", "build/", ".venv/", "*.part")

    fun isExcluded(rel: String, patterns: List<String> = DEFAULT): Boolean {
        val normalized = rel.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/')
        val fileName = segments.last()
        for (pattern in patterns) {
            when {
                pattern.endsWith("/") -> {
                    val dir = pattern.trimEnd('/')
                    if (segments.any { it == dir }) return true
                }
                pattern.startsWith("*") -> {
                    if (fileName.endsWith(pattern.removePrefix("*"))) return true
                }
                else -> {
                    if (normalized == pattern || normalized.startsWith("$pattern/")) return true
                }
            }
        }
        return false
    }
}
