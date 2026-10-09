/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
 *
 * TerminalSessionHost: the runtime that makes sessions actually run.
 *
 * Responsibilities (plan sections 8, 10):
 *  - create a PTY-backed session through an ExecutionBackend
 *  - run one reader coroutine per session feeding a TerminalEmulator
 *  - serialize all writes through a per-session channel (no interleaving)
 *  - debounced resize (TIOCSWINSZ + SIGWINCH is done by the native layer)
 *  - graceful termination: SIGTERM the process group, grace period, SIGKILL
 *  - reap the child once (nativeWait) and drop the finished session so the
 *    foreground service can drain to zero
 *
 * This layer is UI-framework free; the app observes StateFlows and calls the
 * plain methods. Ported to a service so Activity recreation keeps PTYs alive.
 */
package dev.studiorizi.mterm.core.terminal_session

import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.process_supervisor.SupervisedProcess
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionRuntime
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SessionState
import dev.studiorizi.mterm.core.session_core.UnixSignal
import dev.studiorizi.mterm.core.terminal_emulator.TerminalEmulator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Backends keyed by mode; constructed by the app and injected here. */
class TerminalSessionHost(
    val manager: SessionManager,
    private val scope: CoroutineScope,
    val supervisor: ProcessSupervisor = ProcessSupervisor(),
    private val defaultScrollback: Int = TerminalEmulator.DEFAULT_SCROLLBACK_MAX,
) {
    private val emulators = ConcurrentHashMap<String, TerminalEmulator>()
    private val writers = ConcurrentHashMap<String, Channel<ByteArray>>()

    private val _renderTick = MutableStateFlow(0L)

    /** Increments whenever any session produced output; drives UI redraw. */
    val renderTick: StateFlow<Long> = _renderTick.asStateFlow()

    private val _lastFailure = MutableStateFlow<SpawnFailure?>(null)
    val lastFailure: StateFlow<SpawnFailure?> = _lastFailure.asStateFlow()

    private val _lastExitCode = MutableStateFlow<Int?>(null)
    val lastExitCode: StateFlow<Int?> = _lastExitCode.asStateFlow()

    /** Live sessions (finished ones are removed so the map drains to empty). */
    val sessions: StateFlow<Map<String, SessionRuntime>> = manager.sessions

    /** Emulator for a session, or null once it has finished. */
    fun emulator(sessionId: String): TerminalEmulator? = emulators[sessionId]

    /**
     * Creates and starts a session. Returns the new session id, or a failure
     * when the spec is invalid. Spawn failures are reported asynchronously via
     * [lastFailure] (so the caller never blocks on native exec).
     */
    fun open(
        mode: SessionMode,
        title: String,
        command: List<String>,
        env: Map<String, String>,
        cwd: String? = null,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        scrollback: Int = defaultScrollback,
    ): Result<String> {
        val spec = SessionSpec(
            id = UUID.randomUUID().toString(),
            mode = mode,
            title = title,
            cwd = cwd,
            env = env,
            command = command,
        )
        val created = manager.create(spec)
        if (created.isFailure) {
            return Result.failure(created.exceptionOrNull()!!)
        }
        _lastFailure.value = null
        emulators[spec.id] = TerminalEmulator(rows, cols, scrollback)
        scope.launch { startSession(spec.id, rows, cols) }
        _renderTick.value++
        return Result.success(spec.id)
    }

    /** Queues raw input bytes to the session PTY, in order. */
    fun write(sessionId: String, data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len <= 0) return
        val copy = data.copyOfRange(off, off + len)
        writers[sessionId]?.trySend(copy)
    }

    /** Resizes the emulator immediately and the PTY asynchronously. */
    fun resize(sessionId: String, rows: Int, cols: Int) {
        emulators[sessionId]?.resize(rows, cols)
        val pty = manager.get(sessionId)?.pty ?: return
        scope.launch(Dispatchers.IO) {
            try {
                pty.resize(rows, cols)
            } catch (_: Throwable) {
                // A dead session must not crash the UI.
            }
        }
    }

    /** Graceful stop: SIGTERM -> grace -> SIGKILL, then the reader reaps. */
    fun stop(sessionId: String) {
        val runtime = manager.get(sessionId) ?: return
        val process = runtime.process
        if (process == null) {
            cleanup(sessionId, runtime.pty)
            return
        }
        scope.launch {
            try {
                process.signal(UnixSignal.SIGTERM)
            } catch (_: Throwable) {
                // already gone
            }
            if (!awaitFinished(sessionId, GRACE_MS)) {
                try {
                    process.signal(UnixSignal.SIGKILL)
                } catch (_: Throwable) {
                    // already gone
                }
                delay(KILL_MS)
                try {
                    runtime.pty?.close()
                } catch (_: Throwable) {
                    // best effort: unblocks a stuck reader
                }
            }
        }
    }

    fun stopAll() {
        manager.sessions.value.keys.toList().forEach(::stop)
    }

    /**
     * Immediately terminates everything (service teardown). Signals the
     * groups and closes PTYs; the reader coroutines reap and remove.
     */
    fun shutdown() {
        manager.sessions.value.forEach { (id, runtime) ->
            try {
                runtime.process?.signal(UnixSignal.SIGTERM)
            } catch (_: Throwable) {
                // ignore
            }
            try {
                runtime.pty?.close()
            } catch (_: Throwable) {
                // ignore
            }
            cleanup(id, runtime.pty)
        }
    }

    fun clearLastFailure() {
        _lastFailure.value = null
    }

    fun clearLastExit() {
        _lastExitCode.value = null
    }

    private suspend fun startSession(id: String, rows: Int, cols: Int) {
        val result = manager.start(id, rows, cols)
        val throwable = result.exceptionOrNull()
        if (throwable != null) {
            _lastFailure.value = (throwable as? SpawnException)?.failure
                ?: SpawnFailure.BACKEND_UNSUPPORTED
            cleanup(id, manager.get(id)?.pty)
            _renderTick.value++
            return
        }
        val runtime = manager.get(id) ?: return
        val pty = runtime.pty ?: return
        val process = runtime.process ?: return

        // Ordered, non-interleaving writes.
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        writers[id] = channel
        scope.launch(Dispatchers.IO) {
            for (bytes in channel) {
                try {
                    pty.write(bytes, 0, bytes.size)
                } catch (_: Throwable) {
                    // drop on dead session
                }
            }
        }

        if (process.pid > 0) {
            supervisor.register(
                SupervisedProcess(
                    pid = process.pid,
                    pgid = process.pgid,
                    backend = runtime.spec.mode.name,
                    startTime = System.currentTimeMillis(),
                ),
            )
        }
        readLoop(id, pty, process)
    }

    private suspend fun readLoop(id: String, pty: PtyHandle, process: ProcessHandle) {
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (currentCoroutineContext().isActive) {
                val n = pty.read(buffer)
                if (n <= 0) break
                emulators[id]?.write(buffer, 0, n)
                _renderTick.value = _renderTick.value + 1
            }
        } catch (_: Throwable) {
            // Fall through to reaping; a failed read is a normal end.
        } finally {
            val exit = try {
                process.wait()
            } catch (_: Throwable) {
                -1
            }
            if (process.pid > 0) supervisor.unregister(process.pid)
            _lastExitCode.value = exit
            cleanup(id, pty)
            _renderTick.value++
        }
    }

    /** Removes a finished/failed session and releases its resources. */
    private fun cleanup(id: String, pty: PtyHandle?) {
        writers.remove(id)?.close()
        emulators.remove(id)
        try {
            pty?.close()
        } catch (_: Throwable) {
            // idempotent native close
        }
        manager.remove(id)
    }

    private suspend fun awaitFinished(sessionId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (manager.get(sessionId) == null) return true
            delay(POLL_MS)
        }
        return manager.get(sessionId) == null
    }

    companion object {
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
        const val READ_CHUNK = 16 * 1024
        const val GRACE_MS = 1_500L
        const val KILL_MS = 300L
        const val POLL_MS = 30L
    }
}
