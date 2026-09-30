package dev.studiorizi.mterm.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun redactsAppHome() {
        val out = Redactor.redact("exec /data/user/0/dev.studiorizi.mterm/files/linux/bin/sh failed")
        assertFalse(out.contains("dev.studiorizi.mterm"))
        assertTrue(out.contains("<APP_HOME>"))
    }

    @Test
    fun redactsHomeDir() {
        val out = Redactor.redact("cd /home/user/projects")
        assertFalse(out.contains("/home/user"))
        assertTrue(out.contains("<HOME>"))
    }

    @Test
    fun redactsTokens() {
        assertEquals("key=<REDACTED>", Redactor.redact("key=ghp_abcdef123456"))
        assertEquals("key=<REDACTED>", Redactor.redact("key=sk-ant-abc123XYZ_456"))
        assertEquals("tok=<REDACTED>", Redactor.redact("tok=xoxb-1234-abcdef"))
        assertEquals("aws=<REDACTED>", Redactor.redact("aws=AKIAIOSFODNN7EXAMPLE"))
    }

    @Test
    fun masksEmailPartially() {
        val out = Redactor.redact("contact john@example.com now")
        assertFalse(out.contains("john@example.com"))
        assertTrue(out.contains("@example.com"))
    }

    @Test
    fun redactMap() {
        val out = Redactor.redactMap(mapOf("cmd" to "cd /home/user", "plain" to "ok"))
        assertTrue(out["cmd"]!!.contains("<HOME>"))
        assertEquals("ok", out["plain"])
    }
}
