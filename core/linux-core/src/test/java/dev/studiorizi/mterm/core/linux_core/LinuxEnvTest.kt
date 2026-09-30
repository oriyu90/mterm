package dev.studiorizi.mterm.core.linux_core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxEnvTest {

    @Test
    fun `baseEnv contains required keys`() {
        val env = LinuxEnv.baseEnv("/home/user")
        assertEquals("/home/user", env["HOME"])
        assertEquals("user", env["USER"])
        assertEquals("/usr/local/bin:/usr/bin:/bin", env["PATH"])
        assertEquals("/bin/zsh", env["SHELL"])
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `baseEnv honors term override`() {
        assertEquals("screen-256color", LinuxEnv.baseEnv("/h", "screen-256color")["TERM"])
    }

    @Test
    fun `shellArgv defaults to zsh login`() {
        assertEquals(listOf("/bin/zsh", "-l"), LinuxEnv.shellArgv())
    }

    @Test
    fun `androidShell argv exact`() {
        assertEquals(listOf("/system/bin/sh", "-i"), ShellArgv.androidShell())
    }

    @Test
    fun `debianShell argv exact`() {
        assertEquals(listOf("/bin/zsh", "-l"), ShellArgv.debianShell())
    }
}
