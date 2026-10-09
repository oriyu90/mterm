package dev.studiorizi.mterm.core.linux_chroot

import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootBackendTest {

    private fun backend() = ChrootBackend(rootfsDir = File("/data/rootfs"))

    private fun spec(command: List<String> = emptyList()) = SessionSpec(
        id = "s1",
        mode = SessionMode.DEBIAN_CHROOT,
        title = "debian-root",
        cwd = null,
        env = emptyMap(),
        command = command,
    )

    @Test
    fun prepare_argvStartsWithChroot() = runTest {
        val argv = backend().prepare(spec()).argv
        assertEquals("chroot", argv[0])
        assertEquals("/data/rootfs", argv[1])
        assertEquals(ChrootBackend.DEFAULT_SHELL, argv.drop(2))
    }

    @Test
    fun prepare_customCommandReplacesDefaultShell() = runTest {
        val argv = backend().prepare(spec(listOf("/bin/bash", "-l"))).argv
        assertEquals(listOf("chroot", "/data/rootfs", "/bin/bash", "-l"), argv)
    }

    @Test
    fun prepare_noShellMetacharsInAnyElement() = runTest {
        val argv = backend().prepare(spec()).argv
        for (element in argv) {
            assertTrue(
                "shell metachar in argv element: $element",
                !element.contains("&&") && !element.contains(";") && !element.contains("|"),
            )
        }
    }

    @Test
    fun spawn_throwsTypedRootUnsupported() = runTest {
        try {
            backend().prepare(spec()).let { backend().spawn(it, 24, 80) }
            assertTrue("expected SpawnException", false)
        } catch (e: dev.studiorizi.mterm.core.session_core.SpawnException) {
            assertEquals(
                dev.studiorizi.mterm.core.session_core.SpawnFailure.ROOT_UNSUPPORTED,
                e.failure,
            )
        }
    }
}
