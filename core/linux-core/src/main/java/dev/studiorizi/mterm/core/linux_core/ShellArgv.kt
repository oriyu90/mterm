package dev.studiorizi.mterm.core.linux_core

/** Fixed argv arrays for interactive shells. No shell string concatenation. */
object ShellArgv {
    fun androidShell(): List<String> = listOf("/system/bin/sh", "-i")

    fun debianShell(): List<String> = listOf("/bin/zsh", "-l")
}
