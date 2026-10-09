/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.studiorizi.mterm.core.pty_native

/**
 * JNI bridge to the `pty-native` shared library.
 *
 * No Android imports: this object must stay usable from plain JVM unit tests
 * (where the native library is simply absent) and from instrumented code.
 *
 * Lifecycle contract (see `IMPLEMENTATION_PLAN.md` §8):
 * - [nativeSpawnPty] returns a positive handle, or 0 on failure.
 * - `nativeRead` returns bytes read, or -1 on EOF/error (EIO included).
 * - `nativeWrite` returns bytes written, or -1 on error/bounds violation.
 * - `nativeResize` / `nativeSignal` return 0 on success, -1 on error.
 * - `nativeWait` blocks, reaps the child (no zombies) and returns the exit
 *   code (`WEXITSTATUS`, or 128+signal when killed by a signal); -1 on error.
 * - `nativeClose` is idempotent and tolerates unknown handles.
 *
 * Termination policy (enforced by the Kotlin session layer, not here):
 * SIGTERM the whole process group, wait a grace period, then SIGKILL the
 * group, then [nativeWait], then [nativeClose].
 */
object PtyNative {
    init {
        try {
            System.loadLibrary("pty-native")
        } catch (_: UnsatisfiedLinkError) {
            // Absent on plain JVM unit tests; callers must check isAvailable().
        }
    }

    external fun nativeSpawnPty(
        argv: Array<String>,
        env: Array<String>,
        cwd: String?,
        rows: Int,
        columns: Int,
    ): Long

    external fun nativeRead(handle: Long, buffer: ByteArray): Int

    external fun nativeWrite(handle: Long, data: ByteArray, off: Int, len: Int): Int

    /** Child pid for the session leader, or -1 for an unknown handle. */
    external fun nativePid(handle: Long): Int

    external fun nativeResize(handle: Long, rows: Int, columns: Int): Int

    external fun nativeSignal(handle: Long, signal: Int): Int

    external fun nativeWait(handle: Long): Int

    external fun nativeClose(handle: Long)

    /** True when the native library could be loaded. */
    fun isAvailable(): Boolean = try {
        System.loadLibrary("pty-native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /**
     * Encode an environment map as `KEY=VALUE` entries for [nativeSpawnPty].
     * Keys must be non-empty and must not contain `=` or NUL; NUL bytes are
     * rejected in values as well (they would silently truncate in C).
     *
     * @throws IllegalArgumentException on invalid entries.
     */
    fun encodeEnv(map: Map<String, String>): Array<String> =
        map.entries.map { (key, value) ->
            require(key.isNotEmpty()) { "env key must not be empty" }
            require('=' !in key) { "env key must not contain '=': $key" }
            require('\u0000' !in key) { "env key must not contain NUL" }
            require('\u0000' !in value) { "env value must not contain NUL for key: $key" }
            "$key=$value"
        }.toTypedArray()
}
