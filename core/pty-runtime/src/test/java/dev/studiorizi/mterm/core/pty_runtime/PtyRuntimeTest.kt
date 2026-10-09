package dev.studiorizi.mterm.core.pty_runtime

import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PtyRuntimeTest {

    @Test
    fun `spawn on JVM without native throws typed PTY_UNAVAILABLE`() {
        if (PtyRuntime.isAvailable()) return // device runtime: cannot test here
        try {
            PtyRuntime.spawn(listOf("/system/bin/sh", "-c", "exit 0"), rows = 24, cols = 80)
            fail("expected SpawnException")
        } catch (e: SpawnException) {
            assertEquals(SpawnFailure.PTY_UNAVAILABLE, e.failure)
        }
    }

    @Test
    fun `spawn rejects empty argv`() {
        try {
            PtyRuntime.spawn(emptyList(), rows = 24, cols = 80)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("argv"))
        }
    }
}
