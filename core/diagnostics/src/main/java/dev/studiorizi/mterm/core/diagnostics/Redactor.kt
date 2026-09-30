package dev.studiorizi.mterm.core.diagnostics

/**
 * Redacts secrets and personal paths from diagnostics exports.
 * Pure JVM; no Android APIs.
 */
object Redactor {
    private val APP_HOME_RE = Regex("/data/(?:user/\\d+|data)/[^/\\s]+")
    private val HOME_RE = Regex("/home/[^/\\s]+")
    private val TOKEN_RES = listOf(
        Regex("ghp_[A-Za-z0-9]+"),
        Regex("github_pat_[A-Za-z0-9_]+"),
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("xox[bpas]-[A-Za-z0-9\\-]+"),
        Regex("AKIA[0-9A-Z]{16}"),
    )
    private val EMAIL_RE = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    fun redact(text: String): String {
        var out = text
        out = APP_HOME_RE.replace(out, "<APP_HOME>")
        out = HOME_RE.replace(out, "<HOME>")
        for (re in TOKEN_RES) {
            out = re.replace(out, "<REDACTED>")
        }
        out = EMAIL_RE.replace(out) { match ->
            val email = match.value
            val at = email.indexOf('@')
            val user = email.substring(0, at)
            val domain = email.substring(at + 1)
            val masked = if (user.length <= 1) "***" else user.first() + "***"
            "$masked@$domain"
        }
        return out
    }

    fun redactMap(m: Map<String, String>): Map<String, String> =
        m.mapValues { (_, v) -> redact(v) }
}
