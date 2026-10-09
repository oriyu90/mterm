package dev.studiorizi.mterm.core.linux_proot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotArgvTest {

    private fun dirs(): Array<File> =
        Array(4) { File("/data/part$it", if (it == 0) "proot" else "") }

    @Test
    fun build_usesTermuxLongOptions() {
        val (proot, rootfs, bridge, mirror) = dirs()
        val argv = ProotArgv.build(proot, rootfs, bridge, mirror, emptyList())
        assertEquals(File("/data/part0/proot").absolutePath, argv[0])
        val rootfsIndex = argv.indexOf("--rootfs")
        assertTrue(rootfsIndex >= 0)
        assertEquals("/data/part1", argv[rootfsIndex + 1])
        assertTrue(argv.contains("/data/part2:/run/android-bridge"))
        assertTrue(argv.contains("/data/part3:/mnt/shared"))
        val cwdIndex = argv.indexOf("--cwd")
        assertTrue(cwdIndex >= 0)
        assertEquals("/home/user", argv[cwdIndex + 1])
        assertTrue(argv.takeLast(2) == ProotArgv.DEFAULT_SHELL)
    }

    @Test
    fun build_customCommandReplacesDefaultShell() {
        val (proot, rootfs, bridge, mirror) = dirs()
        val argv = ProotArgv.build(
            proot, rootfs, bridge, mirror,
            listOf("/bin/bash", "-c", "echo hi"),
        )
        assertEquals(listOf("/bin/bash", "-c", "echo hi"), argv.takeLast(3))
    }

    @Test
    fun build_noShellMetacharsInAnyElement() {
        val (proot, rootfs, bridge, mirror) = dirs()
        for (element in ProotArgv.build(proot, rootfs, bridge, mirror, emptyList())) {
            assertTrue(
                "shell metachar in argv element: $element",
                !element.contains("&&") && !element.contains(";") && !element.contains("|"),
            )
        }
    }

    @Test
    fun hostEnv_pointsAtBinaryDir() {
        val env = ProotArgv.hostEnv(File("/data/bin/proot"))
        assertEquals(1, env.size)
        assertEquals("LD_LIBRARY_PATH=/data/bin", env[0])
    }
}
