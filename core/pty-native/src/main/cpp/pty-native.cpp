// Copyright 2026 StudioRizi.
// SPDX-License-Identifier: Apache-2.0
//
// pty-native: minimal PTY spawn/read/write/resize/signal/wait/close JNI layer.
//
// Design (IMPLEMENTATION_PLAN.md section 8):
//   /dev/ptmx -> grantpt/unlockpt/ptsname -> open pts -> fork -> setsid ->
//   ioctl(TIOCSCTTY) -> dup2(0/1/2) -> setenv(passed env) -> chdir(cwd) ->
//   execvp(argv[0], argv).
// Each session owns a process group (child is a session/group leader via
// setsid()); termination policy (SIGTERM -> grace period -> SIGKILL on the
// *group*, then wait, then close) is owned by the Kotlin session layer, which
// drives it through nativeSignal/nativeWait/nativeClose. This file only
// provides the primitives plus strict bounds checks (no buffer overflows).
//
// Zombie reaping: nativeWait() performs a blocking waitpid() and erases the
// session, so every spawned child is reaped exactly once provided the caller
// waits (directly or via ProcessSupervisor) before/after closing.

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>

#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#define LOG_TAG "pty-native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    int masterFd = -1;
    pid_t pid = -1;
};

std::mutex g_lock;
std::map<jlong, Session> g_sessions;
jlong g_nextHandle = 1;

constexpr jsize kMaxIoChunk = 8192;
constexpr jsize kMaxWriteCopy = 65536;  // single-call cap; larger writes chunk

// Copy a Java String[] into UTF-8 std::strings. Returns false on OOM or when
// a null element is encountered (caller treats as failure -> return 0/-1).
bool StringArrayToVector(JNIEnv* env, jobjectArray arr, std::vector<std::string>* out,
                         bool allowNullArray) {
    if (arr == nullptr) return allowNullArray;
    const jsize n = env->GetArrayLength(arr);
    out->reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (s == nullptr) return false;
        const char* chars = env->GetStringUTFChars(s, nullptr);
        if (chars == nullptr) {
            env->DeleteLocalRef(s);
            return false;  // OOM: exception pending
        }
        out->emplace_back(chars);
        env->ReleaseStringUTFChars(s, chars);
        env->DeleteLocalRef(s);
        if (env->ExceptionCheck()) return false;
    }
    return true;
}

// Look up a session, copying fd/pid out under lock. Returns false if unknown.
bool LookupSession(jlong handle, Session* out) {
    std::lock_guard<std::mutex> lock(g_lock);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) return false;
    *out = it->second;
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeSpawnPty(
    JNIEnv* env, jobject /*thiz*/, jobjectArray argv, jobjectArray envp, jstring cwd,
    jint rows, jint columns) {
    if (argv == nullptr || env->GetArrayLength(argv) == 0) {
        LOGE("nativeSpawnPty: empty argv");
        return 0;
    }
    std::vector<std::string> args;
    if (!StringArrayToVector(env, argv, &args, /*allowNullArray=*/false)) return 0;
    if (args.empty() || args[0].empty()) {
        LOGE("nativeSpawnPty: empty command");
        return 0;
    }
    std::vector<std::string> envs;
    if (!StringArrayToVector(env, envp, &envs, /*allowNullArray=*/true)) return 0;

    std::string cwdStr;
    bool hasCwd = false;
    if (cwd != nullptr) {
        const char* chars = env->GetStringUTFChars(cwd, nullptr);
        if (chars == nullptr) return 0;  // OOM
        cwdStr.assign(chars);
        env->ReleaseStringUTFChars(cwd, chars);
        hasCwd = !cwdStr.empty();
    }

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        LOGE("nativeSpawnPty: open /dev/ptmx failed: %s", strerror(errno));
        return 0;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("nativeSpawnPty: grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        return 0;
    }
    char* ptsName = ptsname(master);
    if (ptsName == nullptr) {
        LOGE("nativeSpawnPty: ptsname failed: %s", strerror(errno));
        close(master);
        return 0;
    }
    const std::string ptsPath(ptsName);  // copy: ptsname uses a static buffer

    // Initial window size (best effort; failures are non-fatal).
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? (rows > 1000 ? 1000 : rows) : 24);
    ws.ws_col =
        static_cast<unsigned short>(columns > 0 ? (columns > 1000 ? 1000 : columns) : 80);
    (void)ioctl(master, TIOCSWINSZ, &ws);

    int slave = open(ptsPath.c_str(), O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        LOGE("nativeSpawnPty: open %s failed: %s", ptsPath.c_str(), strerror(errno));
        close(master);
        return 0;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        LOGE("nativeSpawnPty: fork failed: %s", strerror(errno));
        close(master);
        close(slave);
        return 0;
    }
    if (pid == 0) {
        // ---- child -----------------------------------------------------
        close(master);
        if (setsid() < 0) _exit(127);
        // Best effort: setsid() already made us a session/group leader, so the
        // session group id equals our pid and the parent can signal the whole
        // group with kill(-pid, sig).
        (void)setpgid(0, 0);
        if (ioctl(slave, TIOCSCTTY, 0) != 0) _exit(127);
        if (dup2(slave, STDIN_FILENO) < 0) _exit(127);
        if (dup2(slave, STDOUT_FILENO) < 0) _exit(127);
        if (dup2(slave, STDERR_FILENO) < 0) _exit(127);
        if (slave > STDERR_FILENO) close(slave);

        // Replace the environment wholesale from the caller's KEY=VALUE list.
        clearenv();
        for (const std::string& e : envs) {
            const size_t pos = e.find('=');
            if (pos == std::string::npos || pos == 0) continue;
            (void)setenv(e.substr(0, pos).c_str(), e.substr(pos + 1).c_str(), 1);
        }
        if (hasCwd) {
            // Best effort: a bad cwd must not silently change semantics, so
            // fail the spawn rather than starting in the wrong directory.
            if (chdir(cwdStr.c_str()) != 0) _exit(127);
        }
        std::vector<char*> cargv;
        cargv.reserve(args.size() + 1);
        for (std::string& a : args) cargv.push_back(a.data());
        cargv.push_back(nullptr);
        execvp(cargv[0], cargv.data());
        _exit(127);  // exec failed
    }
    // ---- parent --------------------------------------------------------
    close(slave);

    std::lock_guard<std::mutex> lock(g_lock);
    const jlong handle = g_nextHandle++;
    g_sessions[handle] = Session{master, pid};
    return handle;
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeRead(JNIEnv* env, jobject /*thiz*/,
                                                                jlong handle, jbyteArray buffer) {
    if (buffer == nullptr) return -1;
    Session s;
    if (!LookupSession(handle, &s)) return -1;
    const jsize cap = env->GetArrayLength(buffer);
    if (cap <= 0) return -1;
    const jsize chunk = cap < kMaxIoChunk ? cap : kMaxIoChunk;
    char tmp[kMaxIoChunk];
    ssize_t n;
    do {
        n = read(s.masterFd, tmp, static_cast<size_t>(chunk));
    } while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;  // EIO (slave side closed), EOF, or EBADF
    env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(n),
                            reinterpret_cast<const jbyte*>(tmp));
    if (env->ExceptionCheck()) return -1;
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeWrite(JNIEnv* env, jobject /*thiz*/,
                                                                 jlong handle, jbyteArray data,
                                                                 jint off, jint len) {
    if (data == nullptr) return -1;
    const jsize cap = env->GetArrayLength(data);
    // Strict bounds check: rejects negative and overflowing off/len.
    if (off < 0 || len < 0 || off > cap || len > cap - off) return -1;
    if (len == 0) return 0;
    if (len > kMaxWriteCopy) len = kMaxWriteCopy;  // chunk large pastes
    Session s;
    if (!LookupSession(handle, &s)) return -1;
    // Bounded stack copy: never touches more than `len` bytes.
    char tmp[kMaxIoChunk];
    jint total = 0;
    while (total < len) {
        const jint want = (len - total) < kMaxIoChunk ? (len - total) : kMaxIoChunk;
        env->GetByteArrayRegion(data, off + total, want, reinterpret_cast<jbyte*>(tmp));
        if (env->ExceptionCheck()) return total > 0 ? total : -1;
        ssize_t n;
        do {
            n = write(s.masterFd, tmp, static_cast<size_t>(want));
        } while (n < 0 && errno == EINTR);
        if (n < 0) return total > 0 ? total : -1;  // EIO/EBADF: session gone
        if (n == 0) break;
        total += static_cast<jint>(n);
    }
    return total;
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativePid(JNIEnv* env, jobject /*thiz*/,
                                                               jlong handle) {
    (void)env;
    Session s;
    if (!LookupSession(handle, &s)) return -1;
    return static_cast<jint>(s.pid);
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeResize(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong handle, jint rows,
                                                                  jint columns) {
    (void)env;
    Session s;
    if (!LookupSession(handle, &s)) return -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? (rows > 1000 ? 1000 : rows) : 24);
    ws.ws_col =
        static_cast<unsigned short>(columns > 0 ? (columns > 1000 ? 1000 : columns) : 80);
    if (ioctl(s.masterFd, TIOCSWINSZ, &ws) != 0) return -1;
    // Notify the foreground process; a dead child (ESRCH) is not an error here.
    if (kill(s.pid, SIGWINCH) != 0 && errno != ESRCH) return -1;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeSignal(JNIEnv* env, jobject /*thiz*/,
                                                                  jlong handle, jint signal) {
    (void)env;
    if (signal <= 0 || signal >= NSIG) return -1;
    Session s;
    if (!LookupSession(handle, &s)) return -1;
    // The child is a session/group leader: signal the whole group first so
    // descendants (node/npm/MCP children) die with the session, falling back
    // to the bare pid when the group is already gone.
    if (kill(-s.pid, signal) == 0) return 0;
    if (errno != ESRCH) return -1;
    return kill(s.pid, signal) == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeWait(JNIEnv* env, jobject /*thiz*/,
                                                                jlong handle) {
    (void)env;
    Session s;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return -1;
        s = it->second;
    }
    int status = 0;
    pid_t w;
    do {
        w = waitpid(s.pid, &status, 0);
    } while (w < 0 && errno == EINTR);
    // Reap exactly once: erase the session and close the PTY fd here so a
    // missing nativeClose() cannot leak fds or leave zombies. nativeClose()
    // tolerates the now-unknown handle, and a blocked reader observes EOF/EIO.
    {
        std::lock_guard<std::mutex> lock(g_lock);
        auto it = g_sessions.find(handle);
        if (it != g_sessions.end()) {
            if (it->second.masterFd >= 0) close(it->second.masterFd);
            g_sessions.erase(it);
        }
    }
    if (w < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_dev_studiorizi_mterm_core_pty_1native_PtyNative_nativeClose(JNIEnv* env, jobject /*thiz*/,
                                                                 jlong handle) {
    (void)env;
    // Idempotent: unknown handles (never existed, or already waited) are a
    // silent no-op so double-close races between the reader and the session
    // teardown cannot crash. This never kills the child; the Kotlin layer
    // signals (SIGTERM -> grace -> SIGKILL on the group) and waits first.
    std::lock_guard<std::mutex> lock(g_lock);
    auto it = g_sessions.find(handle);
    if (it == g_sessions.end()) return;
    if (it->second.masterFd >= 0) close(it->second.masterFd);
    g_sessions.erase(it);
}

}  // extern "C"
