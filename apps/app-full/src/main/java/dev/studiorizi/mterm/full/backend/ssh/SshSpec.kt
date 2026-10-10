package dev.studiorizi.mterm.full.backend.ssh

/**
 * Parsed SSH connection parameters.
 *
 * Secrets (password, key bytes) never travel in [dev.studiorizi.mterm.core.session_core.SessionSpec.env]:
 * the dialog stores them in [SshCredentialStore] under a one-shot token and
 * only the token crosses the service boundary. The store is wiped right after
 * authentication (success or failure).
 */
data class SshParams(
    val host: String,
    val port: Int,
    val user: String,
    val auth: Auth,
) {
    sealed interface Auth {
        /** Token into [SshCredentialStore] holding the password chars. */
        data class Password(val credentialToken: String) : Auth

        /** Token holding key PEM bytes (+ optional passphrase token). */
        data class Key(val credentialToken: String, val passphraseToken: String?) : Auth
    }

    companion object {
        const val ENV_HOST = "MTERM_SSH_HOST"
        const val ENV_PORT = "MTERM_SSH_PORT"
        const val ENV_USER = "MTERM_SSH_USER"
        const val ENV_AUTH = "MTERM_SSH_AUTH"
        const val ENV_CRED = "MTERM_SSH_CRED"
        const val ENV_KEY_PASS = "MTERM_SSH_KEY_PASS"
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"

        const val DEFAULT_PORT = 22
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        /** Parses spec env; throws [IllegalArgumentException] on bad input. */
        fun parse(env: Map<String, String>): SshParams {            val host = env[ENV_HOST]?.trim().orEmpty()
            require(host.isNotEmpty() && '\u0000' !in host && !host.contains(Regex("\\s"))) {
                "bad SSH host"
            }
            val portRaw = env[ENV_PORT]
            val port = if (portRaw == null) {
                DEFAULT_PORT
            } else {
                portRaw.toIntOrNull()
                    ?: throw IllegalArgumentException("bad SSH port: $portRaw")
            }
            require(port in MIN_PORT..MAX_PORT) { "bad SSH port: $portRaw" }
            val user = env[ENV_USER]?.trim().orEmpty()
            require(user.isNotEmpty() && '\u0000' !in user && !user.contains(Regex("\\s"))) {
                "bad SSH user"
            }
            val cred = env[ENV_CRED].orEmpty()
            require(cred.isNotEmpty()) { "missing SSH credential token" }
            val auth = when (env[ENV_AUTH]) {
                AUTH_KEY -> Auth.Key(cred, env[ENV_KEY_PASS]?.takeIf { it.isNotEmpty() })
                else -> Auth.Password(cred)
            }
            return SshParams(host, port, user, auth)
        }

        /**
         * Normalizes dialog input: Japanese IMEs often commit full-width
         * alphanumerics (１２７．０．０．１); SSH never accepts those, so map
         * U+FF01–FF5E and U+3000 to their half-width forms. Applied by the
         * ViewModel before validation so logs/errors show the effective value.
         */
        fun normalizeInput(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (c in raw) {
                when (c) {
                    '\u3000' -> sb.append(' ')
                    in '\uFF01'..'\uFF5E' -> sb.append((c.code - 0xFEE0).toChar())
                    else -> sb.append(c)
                }
            }
            return sb.toString().trim()
        }
    }
}
