package dev.studiorizi.mterm.modern.backend

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gate C qualification for targetSdk 36 (plan section 17.1).
 *
 * Probes whether an app-private copy of the system shell executes (W^X
 * check) and whether that copy can spawn a nested child process. All
 * commands are fixed argv arrays; no user input is interpolated and no
 * paths or secrets are logged.
 */
data class ExecBrokerQualification(
    val appDataExec: String,
    val nestedExec: String,
    val detail: String,
)

object ExecBrokerQualifier {

    fun qualify(appFilesDir: File?): ExecBrokerQualification {
        if (appFilesDir == null) {
            return ExecBrokerQualification("SKIP", "SKIP", "device runtime required")
        }
        val systemShell = File("/system/bin/sh")
        if (!systemShell.isFile || !systemShell.canExecute()) {
            return ExecBrokerQualification("SKIP", "SKIP", "device runtime required")
        }
        val probeDir = File(appFilesDir, "execbroker")
        return try {
            probeDir.mkdirs()
            val probe = File(probeDir, "sh-probe")
            systemShell.copyTo(probe, overwrite = true)
            probe.setExecutable(true)
            val appDataOk = runProbe(listOf(probe.absolutePath, "-c", "exit 0"))
            val nestedOk = if (appDataOk) {
                runProbe(listOf(probe.absolutePath, "-c", "/system/bin/sh -c 'exit 0'"))
            } else {
                false
            }
            ExecBrokerQualification(
                appDataExec = if (appDataOk) "PASS" else "FAIL",
                nestedExec = if (!appDataOk) "SKIP" else if (nestedOk) "PASS" else "FAIL",
                detail = "Gate C probe via app-private shell copy; fixed argv only",
            )
        } catch (_: Exception) {
            ExecBrokerQualification("FAIL", "SKIP", "probe error")
        } finally {
            try {
                File(probeDir, "sh-probe").delete()
            } catch (_: Exception) {
                // Best effort cleanup; never throws.
            }
        }
    }

    private fun runProbe(argv: List<String>): Boolean {
        return try {
            val process = ProcessBuilder(argv).start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }
    }
}
