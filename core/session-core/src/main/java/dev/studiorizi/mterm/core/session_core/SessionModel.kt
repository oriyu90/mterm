package dev.studiorizi.mterm.core.session_core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Which backend a session runs on. UI layers must not branch on root access directly. */
enum class SessionMode {
    ANDROID_SHELL,
    DEBIAN_PROOT,
    DEBIAN_CHROOT,
    SSH,
}

/** Lifecycle state of a single terminal session. */
enum class SessionState {
    CREATED,
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
}

/** Unix signals addressable by name. Numbers are Linux values. */
enum class UnixSignal(val num: Int) {
    SIGTERM(15),
    SIGKILL(9),
    SIGWINCH(28),
    SIGINT(2),
    SIGTSTP(20),
}

/**
 * Declarative request to open a session.
 *
 * @param command argv array elements; never a shell string (no concatenation).
 */
data class SessionSpec(
    val id: String,
    val mode: SessionMode,
    val title: String,
    val cwd: String?,
    val env: Map<String, String>,
    val command: List<String>,
)

/** Backend-validated spawn plan: argv list plus KEY=VALUE env array. */
data class PreparedSession(
    val spec: SessionSpec,
    val argv: List<String>,
    val env: Array<String>,
    val cwd: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedSession) return false
        return spec == other.spec &&
            argv == other.argv &&
            env.contentEquals(other.env) &&
            cwd == other.cwd
    }

    override fun hashCode(): Int {
        var result = spec.hashCode()
        result = 31 * result + argv.hashCode()
        result = 31 * result + env.contentHashCode()
        result = 31 * result + (cwd?.hashCode() ?: 0)
        return result
    }
}

/**
 * Opaque PTY endpoint. Implemented by the pty-native layer; UI never touches fds.
 *
 * [read]/[write] carry default unsupported implementations so non-native
 * handles (fakes, future transports) remain source-compatible.
 */
interface PtyHandle {
    val id: Long

    /** Reads up to `buffer.size` bytes. Returns >0 count, or -1 on EOF/error. */
    fun read(buffer: ByteArray): Int = -1

    /** Writes `data[off, off+len)`. Returns bytes written, or -1 on error. */
    fun write(data: ByteArray, off: Int, len: Int): Int = -1

    fun resize(rows: Int, cols: Int)
    fun close()
}

/** Reason a backend could not spawn, mapped to a localized UI message. */
enum class SpawnFailure {
    PTY_UNAVAILABLE,
    PROOT_MISSING,
    ROOTFS_MISSING,
    ROOT_UNSUPPORTED,
    BACKEND_UNSUPPORTED,
}

/** Typed spawn failure; never a bare generic exception across the UI boundary. */
class SpawnException(
    val failure: SpawnFailure,
    message: String? = null,
) : Exception(message ?: failure.name)

/** Result of a successful [ExecutionBackend.spawn]: the PTY plus its child. */
data class SpawnedProcess(
    val pty: PtyHandle,
    val process: ProcessHandle,
)

/** Opaque child process endpoint. wait() reaps via waitpid in the native layer. */
interface ProcessHandle {
    val pid: Int
    val pgid: Int
    suspend fun wait(): Int
    fun signal(sig: UnixSignal)
}

/** Backend contract shared by Android shell / PRoot / chroot / SSH. */
interface ExecutionBackend {
    val mode: SessionMode

    suspend fun prepare(spec: SessionSpec): PreparedSession

    /**
     * Spawns the prepared session on a fresh PTY sized [rows] x [cols].
     * Native backends return a [SpawnedProcess]; unsupported ones throw
     * [SpawnException] with a specific [SpawnFailure].
     */
    suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess

    suspend fun stop(handle: ProcessHandle, signal: UnixSignal = UnixSignal.SIGTERM)
}

/** Live session entry exposed to UI as immutable snapshot plus observable state. */
data class SessionRuntime(
    val spec: SessionSpec,
    val state: MutableStateFlow<SessionState>,
    val process: ProcessHandle?,
    val pty: PtyHandle?,
    val createdAt: Long,
)

/**
 * Owns session lifecycle. All PTY-channel access is serialized through per-session
 * [Mutex]es via [withPty]; UI layers must go through here instead of calling
 * native handles directly.
 */
class SessionManager(
    private val backends: Map<SessionMode, ExecutionBackend>,
) {
    private val _sessions = MutableStateFlow<Map<String, SessionRuntime>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionRuntime>> = _sessions.asStateFlow()

    private val ptyMutexes = ConcurrentHashMap<String, Mutex>()

    fun get(sessionId: String): SessionRuntime? = _sessions.value[sessionId]

    fun create(spec: SessionSpec): Result<SessionRuntime> {
        val validation = SessionValidator.validate(spec)
        if (validation.isFailure) {
            return Result.failure(validation.exceptionOrNull()!!)
        }
        val runtime = SessionRuntime(
            spec = spec,
            state = MutableStateFlow(SessionState.CREATED),
            process = null,
            pty = null,
            createdAt = System.currentTimeMillis(),
        )
        var inserted = false
        _sessions.update { current ->
            if (current.containsKey(spec.id)) {
                current
            } else {
                inserted = true
                current + (spec.id to runtime)
            }
        }
        return if (inserted) {
            ptyMutexes.getOrPut(spec.id) { Mutex() }
            Result.success(runtime)
        } else {
            Result.failure(IllegalStateException("Session already exists: ${spec.id}"))
        }
    }

    suspend fun start(sessionId: String, rows: Int, cols: Int): Result<Unit> {
        val runtime = _sessions.value[sessionId]
            ?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
        val backend = backends[runtime.spec.mode]
            ?: return Result.failure(IllegalStateException("No backend for mode ${runtime.spec.mode}"))
        runtime.state.value = SessionState.STARTING
        return try {
            val prepared = backend.prepare(runtime.spec)
            val spawned = backend.spawn(prepared, rows, cols)
            _sessions.update { current ->
                current + (sessionId to runtime.copy(process = spawned.process, pty = spawned.pty))
            }
            runtime.state.value = SessionState.RUNNING
            Result.success(Unit)
        } catch (t: Throwable) {
            runtime.state.value = SessionState.FAILED
            Result.failure(t)
        }
    }

    /** Marks a session EXITED (reader loop / explicit stop). Never throws. */
    fun markExited(sessionId: String) {
        _sessions.value[sessionId]?.state?.value = SessionState.EXITED
    }

    /** Marks a session FAILED (unrecoverable I/O error). Never throws. */
    fun markFailed(sessionId: String) {
        _sessions.value[sessionId]?.state?.value = SessionState.FAILED
    }

    /** Removes a finished session so the live map (and FGS) can drain to empty. */
    fun remove(sessionId: String) {
        _sessions.update { it - sessionId }
        ptyMutexes.remove(sessionId)
    }

    suspend fun stop(sessionId: String, signal: UnixSignal = UnixSignal.SIGTERM): Result<Unit> {
        val runtime = _sessions.value[sessionId]
            ?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
        return try {
            val handle = runtime.process
            if (handle != null) {
                val backend = backends[runtime.spec.mode]
                    ?: return Result.failure(
                        IllegalStateException("No backend for mode ${runtime.spec.mode}"),
                    )
                backend.stop(handle, signal)
            }
            runtime.pty?.let(::safeClose)
            runtime.state.value = SessionState.EXITED
            Result.success(Unit)
        } catch (t: Throwable) {
            runtime.state.value = SessionState.FAILED
            Result.failure(t)
        }
    }

    suspend fun stopAll(signal: UnixSignal = UnixSignal.SIGTERM) {
        val ids = _sessions.value.keys.toList()
        for (id in ids) {
            stop(id, signal)
        }
    }

    /**
     * Runs [block] against the session PTY while holding the session mutex,
     * so concurrent writers (UI, bridge, macro playback) cannot interleave bytes.
     */
    suspend fun <T> withPty(sessionId: String, block: suspend (PtyHandle) -> T): Result<T> {
        val runtime = _sessions.value[sessionId]
            ?: return Result.failure(NoSuchElementException("Unknown session: $sessionId"))
        val pty = runtime.pty
            ?: return Result.failure(IllegalStateException("Session has no PTY: $sessionId"))
        val mutex = ptyMutexes.getOrPut(sessionId) { Mutex() }
        return try {
            Result.success(mutex.withLock { block(pty) })
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Never throws: close paths must be crash-safe. */
    private fun safeClose(pty: PtyHandle) {
        try {
            pty.close()
        } catch (_: Throwable) {
            // Swallow: destructors/close must never throw.
        }
    }
}
