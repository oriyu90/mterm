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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Set once [shutdown] runs. New sessions are refused afterwards, and a
     * spawn that completes after teardown is reaped immediately instead of
     * resurrecting a session nobody owns.
     */
    private val closed = AtomicBoolean(false)

    private val _droppedInput = MutableStateFlow(0L)

    /** Total input bytes dropped because a session's write queue was full. */
    val droppedInput: StateFlow<Long> = _droppedInput.asStateFlow()

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
        if (closed.get()) {
            return Result.failure(IllegalStateException("session host is closed"))
        }
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
        _renderTick.update { it + 1 }
        return Result.success(spec.id)
    }

    /**
     * Queues raw input bytes to the session PTY, in order. The queue is
     * bounded ([WRITE_QUEUE]); when a stalled session can't drain, the
     * oldest input is dropped and counted in [droppedInput] rather than
     * growing memory without bound.
     */
    fun write(sessionId: String, data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len <= 0) return
        val channel = writers[sessionId] ?: return
        val copy = data.copyOfRange(off, off + len)
        if (!channel.trySend(copy).isSuccess) {
            _droppedInput.update { it + len }
        }
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
     * Immediately terminates everything (service teardown). Signals all
     * sessions, reaps them (wait before close, so no zombie), then removes
     * them. Blocks the caller (bounded by [SHUTDOWN_REAP_MS] plus close);
     * call only from teardown paths such as `Service.onDestroy`.
     * Idempotent.
     */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = manager.sessions.value.toList()
        snapshot.forEach { (_, runtime) ->
            try {
                runtime.process?.signal(UnixSignal.SIGTERM)
            } catch (_: Throwable) {
                // ignore
            }
        }
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                snapshot.map { (id, runtime) ->
                    async {
                        try {
                            withTimeoutOrNull(SHUTDOWN_REAP_MS) {
                                runtime.process?.wait()
                            }
                        } catch (_: Throwable) {
                            // fall through to force-close below
                        }
                        try {
                            runtime.process?.signal(UnixSignal.SIGKILL)
                        } catch (_: Throwable) {
                            // already gone
                        }
                        cleanup(id, manager.get(id)?.pty ?: runtime.pty)
                    }
                }.awaitAll()
            }
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
            _renderTick.update { it + 1 }
            return
        }
        val runtime = manager.get(id) ?: return
        if (closed.get()) {
            // Stopped while spawn was in flight: reap immediately instead of
            // resurrecting a session nobody owns.
            try {
                runtime.process?.signal(UnixSignal.SIGTERM)
            } catch (_: Throwable) {
            }
            cleanup(id, runtime.pty)
            return
        }
        val pty = runtime.pty ?: return
        val process = runtime.process ?: return

        // Ordered, non-interleaving writes.
        val channel = Channel<ByteArray>(WRITE_QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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
        // Blocking reads must not park Dispatchers.Default threads (pool is
        // CPU-sized; one thread would stall per session).
        scope.launch(Dispatchers.IO) { readLoop(id, pty, process) }
    }

    private suspend fun readLoop(id: String, pty: PtyHandle, process: ProcessHandle) {
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (currentCoroutineContext().isActive) {
                val n = pty.read(buffer)
                if (n <= 0) break
                emulators[id]?.write(buffer, 0, n)
                _renderTick.update { it + 1 }
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
            _renderTick.update { it + 1 }
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

        /** Bounded per-session input queue (oldest dropped past capacity). */
        const val WRITE_QUEUE = 512

        /** Grace period per session for the async shutdown reaper. */
        const val SHUTDOWN_REAP_MS = 2_500L
    }
}
