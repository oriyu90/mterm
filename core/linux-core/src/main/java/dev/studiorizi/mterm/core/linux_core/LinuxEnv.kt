package dev.studiorizi.mterm.core.linux_core

/** Default environment and login-shell argv for the Debian guest. No string concat. */
object LinuxEnv {
    fun baseEnv(home: String, term: String = "xterm-256color"): Map<String, String> =
        mapOf(
            "HOME" to home,
            "USER" to "user",
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "SHELL" to "/bin/zsh",
            "TERM" to term,
        )

    fun shellArgv(shell: String = "/bin/zsh"): List<String> = listOf(shell, "-l")
}
