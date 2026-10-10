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
        // Long options take `=`-joined values (space form is rejected).
        assertTrue(argv.contains("--rootfs=/data/part1"))
        assertTrue(argv.contains("--bind=/data/part2:/run/android-bridge"))
        // The whole shared tree (parent of mirrorDir) is bound so SAF mounts
        // and the inbox stay reachable without per-mount binds.
        assertTrue(argv.contains("--bind=/data:/mnt/shared"))
        assertTrue(argv.contains("--cwd=/home/user"))
        // System binds give the guest working /dev//proc//sys (the tarball
        // cannot carry device nodes).
        assertTrue(argv.contains("--bind=/dev"))
        assertTrue(argv.contains("--bind=/proc"))
        assertTrue(argv.contains("--bind=/sys"))
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("UV_USE_IO_URING=0"))
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
    fun hostEnv_pointsAtBinaryDirTmpAndLoaders() {
        val env = ProotArgv.hostEnv(File("/data/bin/proot"), File("/data/tmp"))
        assertEquals(4, env.size)
        assertEquals("LD_LIBRARY_PATH=/data/bin", env[0])
        assertEquals("PROOT_TMP_DIR=/data/tmp", env[1])
        assertEquals("PROOT_LOADER=/data/bin/loader", env[2])
        assertEquals("PROOT_LOADER_32=/data/bin/loader32", env[3])
    }

    @Test
    fun extraGuestEnv_appendsAfterFixedEnv() {
        val (proot, rootfs, bridge, mirror) = dirs()
        val argv = ProotArgv.build(
            proot, rootfs, bridge, mirror, emptyList(),
            extraGuestEnv = listOf("MTERM_BRIDGE_SOCK=@mterm-bridge-1"),
        )
        val envIndex = argv.indexOf("/usr/bin/env")
        assertTrue(envIndex >= 0)
        assertTrue(argv.contains("MTERM_BRIDGE_SOCK=@mterm-bridge-1"))
        // Extra env rides after the fixed block, before the shell tail.
        assertTrue(argv.indexOf("MTERM_BRIDGE_SOCK=@mterm-bridge-1") > argv.indexOf("SHELL=/bin/zsh"))
        assertTrue(argv.takeLast(2) == ProotArgv.DEFAULT_SHELL)
    }

    @Test
    fun rootfsFromArgv_roundTrips() {
        val (proot, rootfs, bridge, mirror) = dirs()
        val argv = ProotArgv.build(proot, rootfs, bridge, mirror, emptyList())
        assertEquals(File("/data/part1"), ProotArgv.rootfsFromArgv(argv))
        assertEquals(null, ProotArgv.rootfsFromArgv(listOf("proot", "--cwd=/x")))
    }
}
