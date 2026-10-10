package dev.studiorizi.mterm.full.backend.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SshSpecTest {
    private fun env(
        host: String = "example.com",
        port: String = "22",
        user: String = "bob",
        auth: String = "password",
        cred: String = "tok",
    ) = mapOf(
        SshParams.ENV_HOST to host,
        SshParams.ENV_PORT to port,
        SshParams.ENV_USER to user,
        SshParams.ENV_AUTH to auth,
        SshParams.ENV_CRED to cred,
    )

    @Test
    fun `parses password params`() {
        val p = SshParams.parse(env())
        assertEquals("example.com", p.host)
        assertEquals(22, p.port)
        assertEquals("bob", p.user)
        assertTrue(p.auth is SshParams.Auth.Password)
    }

    @Test
    fun `parses key params with passphrase token`() {
        val p = SshParams.parse(env(auth = "key") + (SshParams.ENV_KEY_PASS to "pt"))
        val auth = p.auth as SshParams.Auth.Key
        assertEquals("tok", auth.credentialToken)
        assertEquals("pt", auth.passphraseToken)
    }

    @Test
    fun `rejects blank host`() {
        try {
            SshParams.parse(env(host = "  "))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects host with whitespace`() {
        try {
            SshParams.parse(env(host = "exa mple.com"))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects bad ports`() {
        for (port in listOf("0", "65536", "-1", "abc", "")) {
            try {
                SshParams.parse(env(port = port))
                fail("expected failure for $port")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `rejects blank user and missing credential`() {
        try {
            SshParams.parse(env(user = ""))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SshParams.parse(env(cred = ""))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects user with whitespace`() {
        try {
            SshParams.parse(env(user = "b ob"))
            fail("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `unknown auth kind falls back to password`() {
        val p = SshParams.parse(env(auth = "keyboard"))
        assertTrue(p.auth is SshParams.Auth.Password)
    }

    @Test
    fun `normalizeInput folds full-width`() {
        assertEquals("127.0.0.1", SshParams.normalizeInput("１２７．０．０．１"))
        assertEquals("mtm", SshParams.normalizeInput("　ｍｔｍ　"))
        assertEquals("", SshParams.normalizeInput("　"))
    }
}
