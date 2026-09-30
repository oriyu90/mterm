package dev.studiorizi.mterm.core.linux_proot

import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotBackendTest {

    private fun backend(): ProotBackend = ProotBackend(
        rootfsDir = File("/data/rootfs"),
        bridgeDir = File("/data/bridge"),
        mirrorDir = File("/data/mirror"),
        prootBin = File("/data/proot"),
    )

    private fun spec(command: List<String> = emptyList()) = SessionSpec(
        id = "s1",
        mode = SessionMode.DEBIAN_PROOT,
        title = "debian",
        cwd = null,
        env = emptyMap(),
        command = command,
    )

    @Test
    fun prepare_buildsArgvWithRootfsAndBinds() = runTest {
        val prepared = backend().prepare(spec())
        val argv = prepared.argv
        val rootfsIndex = argv.indexOf("--rootfs")
        assertTrue(rootfsIndex >= 0)
        assertEquals("/data/rootfs", argv[rootfsIndex + 1])
        assertTrue(argv.contains("/data/bridge:/run/android-bridge"))
        assertTrue(argv.contains("/data/mirror:/mnt/shared"))
        assertTrue(argv.takeLast(2) == ProotBackend.DEFAULT_SHELL)
    }

    @Test
    fun prepare_customCommandReplacesDefaultShell() = runTest {
        val prepared = backend().prepare(spec(listOf("/bin/bash", "-c", "echo hi")))
        assertEquals(listOf("/bin/bash", "-c", "echo hi"), prepared.argv.takeLast(3))
    }

    @Test
    fun prepare_noShellMetacharsInAnyElement() = runTest {
        // Our argv construction never joins elements with shell operators;
        // a custom guest command travels as separate argv elements.
        val argv = backend().prepare(spec()).argv
        for (element in argv) {
            assertTrue(
                "shell metachar in argv element: $element",
                !element.contains("&&") && !element.contains(";") && !element.contains("|"),
            )
        }
    }
}
