package dev.studiorizi.mterm.core.linux_proot

import dev.studiorizi.mterm.core.pty_runtime.PtyRuntime
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.UnixSignal
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-shot guest command runner for doctor checks and preset installers.
 *
 * Builds the exact same argv as interactive sessions ([ProotArgv]) but runs
 * non-interactively: spawn `/bin/sh -c <script>` on a PTY, collect output
 * until EOF or [timeoutMs], then reap. PTY (not pipes) is deliberate: guest
 * programs that check `isatty` behave the same as in a real session.
 *
 * Output is capped ([MAX_OUTPUT_BYTES]) so a runaway guest cannot OOM the
 * app. Never throws: everything surfaces as a failed [Result].
 */
object GuestProbe {

    const val MAX_OUTPUT_BYTES = 256 * 1024

    data class ProbeResult(val output: String, val exitCode: Int)

    suspend fun run(
        prootBin: File,
        rootfsDir: File,
        bridgeDir: File,
        mirrorDir: File,
        tmpDir: File,
        script: String,
        timeoutMs: Long = 60_000,
    ): Result<ProbeResult> = withContext(Dispatchers.IO) {
        try {
            if (!prootBin.isFile || !prootBin.canExecute()) {
                return@withContext Result.failure(
                    SpawnException(SpawnFailure.PROOT_MISSING, "proot not installed"),
                )
            }
            if (!rootfsDir.isDirectory) {
                return@withContext Result.failure(
                    SpawnException(SpawnFailure.ROOTFS_MISSING, "rootfs not installed"),
                )
            }
            runCatching { bridgeDir.mkdirs() }
            runCatching { mirrorDir.mkdirs() }
            runCatching { tmpDir.mkdirs() }
            val argv = ProotArgv.build(
                prootBin,
                rootfsDir,
                bridgeDir,
                mirrorDir,
                listOf("/bin/sh", "-c", script),
            )
            // Watchdog, not coroutine timeout: the PTY read below blocks in
            // JNI (uncancellable), so on expiry we SIGKILL the group and
            // close the master fd, which unblocks the reader with EOF/EIO.
            // Without this, one wedged guest would freeze the UI forever.
            return@withContext coroutineScope {
                val process = PtyRuntime.spawn(
                    argv = argv,
                    env = ProotArgv.hostEnv(prootBin, tmpDir).toList(),
                    cwd = null,
                    rows = 24,
                    cols = 80,
                )
                val timedOut = AtomicBoolean(false)
                val watchdog = launch(Dispatchers.Default) {
                    delay(timeoutMs)
                    timedOut.set(true)
                    runCatching { process.signal(UnixSignal.SIGKILL) }
                    runCatching { process.close() }
                }
                try {
                    val buffer = ByteArray(16 * 1024)
                    val collected = StringBuilder()
                    var total = 0
                    while (true) {
                        val n = process.read(buffer)
                        if (n <= 0) break
                        val room = MAX_OUTPUT_BYTES - total
                        if (room <= 0) break
                        val take = minOf(n, room)
                        collected.append(String(buffer, 0, take, Charsets.UTF_8))
                        total += take
                    }
                    val exit = process.wait()
                    if (timedOut.get()) {
                        Result.failure(
                            IllegalStateException("guest command timed out after ${timeoutMs}ms"),
                        )
                    } else {
                        Result.success(ProbeResult(collected.toString(), exit))
                    }
                } catch (e: Exception) {
                    if (timedOut.get()) {
                        Result.failure(
                            IllegalStateException("guest command timed out after ${timeoutMs}ms"),
                        )
                    } else {
                        Result.failure(e)
                    }
                } finally {
                    watchdog.cancel()
                    runCatching { process.close() }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
