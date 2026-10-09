/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Native PTY-backed process: implements both PtyHandle and ProcessHandle so
 * the session layer can drive one object. No Android framework imports here;
 * this is a thin, crash-safe wrapper over the pty-native JNI primitives.
 */
package dev.studiorizi.mterm.core.pty_runtime

import dev.studiorizi.mterm.core.pty_native.PtyNative
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.UnixSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A spawned PTY child (session/group leader).
 *
 * Termination policy (SIGTERM a process group -> grace period -> SIGKILL)
 * is owned by the session layer; this class only exposes the primitives.
 * [read] returns -1 on EOF/EIO once the child closes its side, which is the
 * signal the reader loop uses to reap via [wait].
 */
class PtyProcess internal constructor(
    private val handle: Long,
    override val pid: Int,
) : PtyHandle, ProcessHandle {

    /** Native session handle; also the [PtyHandle.id]. */
    override val id: Long get() = handle

    /** Child is a session leader via setsid(), so its group id equals pid. */
    override val pgid: Int get() = pid

    override fun read(buffer: ByteArray): Int = PtyNative.nativeRead(handle, buffer)

    override fun write(data: ByteArray, off: Int, len: Int): Int =
        PtyNative.nativeWrite(handle, data, off, len)

    override fun resize(rows: Int, cols: Int) {
        PtyNative.nativeResize(handle, rows, cols)
    }

    override fun close() {
        PtyNative.nativeClose(handle)
    }

    /** Reaps the child. Blocks; run off the main thread. */
    override suspend fun wait(): Int = withContext(Dispatchers.IO) {
        PtyNative.nativeWait(handle)
    }

    override fun signal(sig: UnixSignal) {
        PtyNative.nativeSignal(handle, sig.num)
    }

    companion object {
        /** Native handle for a value produced by [PtyRuntime.spawn]. */
        fun of(handle: Long, pid: Int): PtyProcess = PtyProcess(handle, pid)
    }
}

/** Spawns PTY children. Pure wrapper: all state lives in the native layer. */
object PtyRuntime {

    /** True when the native library loaded successfully. */
    fun isAvailable(): Boolean = PtyNative.isAvailable()

    /**
     * Spawns [argv] on a fresh PTY.
     *
     * @throws SpawnException with a specific [SpawnFailure] when the native
     *   library is absent, argv is empty, or the fork/exec fails.
     */
    fun spawn(
        argv: List<String>,
        env: List<String> = emptyList(),
        cwd: String? = null,
        rows: Int,
        cols: Int,
    ): PtyProcess {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        if (!PtyNative.isAvailable()) {
            throw SpawnException(SpawnFailure.PTY_UNAVAILABLE, "pty-native library not loaded")
        }
        val handle = PtyNative.nativeSpawnPty(
            argv.toTypedArray(),
            env.toTypedArray(),
            cwd,
            rows.coerceIn(1, 1000),
            cols.coerceIn(1, 1000),
        )
        if (handle <= 0L) {
            throw SpawnException(
                SpawnFailure.PTY_UNAVAILABLE,
                "nativeSpawnPty failed for ${argv.firstOrNull()}",
            )
        }
        val pid = PtyNative.nativePid(handle)
        return PtyProcess.of(handle, if (pid > 0) pid else -1)
    }

    /**
     * Round-trips `/system/bin/sh -c "printf ok"` through the PTY and returns
     * true only when the child exits 0 with observable output. Used by the
     * diagnostics screen for a real (not library-presence) PTY check.
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        if (!PtyNative.isAvailable()) return@withContext false
        val proc = try {
            spawn(
                argv = listOf("/system/bin/sh", "-c", "printf mterm-pty-ok"),
                rows = 24,
                cols = 80,
            )
        } catch (_: Throwable) {
            return@withContext false
        }
        try {
            val buf = ByteArray(256)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val n = proc.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.contains("mterm-pty-ok")) break
            }
            val exit = proc.wait()
            sb.contains("mterm-pty-ok") && exit == 0
        } catch (_: Throwable) {
            false
        } finally {
            try {
                proc.close()
            } catch (_: Throwable) {
                // close is best-effort
            }
        }
    }
}
