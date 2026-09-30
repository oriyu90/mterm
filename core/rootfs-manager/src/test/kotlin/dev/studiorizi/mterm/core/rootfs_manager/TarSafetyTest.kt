package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarSafetyTest {

    @Test
    fun rejectsAbsoluteAndTraversal() {
        assertFalse(TarSafety.isSafeEntry(""))
        assertFalse(TarSafety.isSafeEntry("/etc/passwd"))
        assertFalse(TarSafety.isSafeEntry("a/../../b"))
        assertFalse(TarSafety.isSafeEntry(".."))
        assertFalse(TarSafety.isSafeEntry("../x"))
        assertFalse(TarSafety.isSafeEntry("a/../.."))
    }

    @Test
    fun acceptsNormalRelativePaths() {
        assertTrue(TarSafety.isSafeEntry("usr/bin/bash"))
        assertTrue(TarSafety.isSafeEntry("etc/hosts"))
        assertTrue(TarSafety.isSafeEntry("a/./b"))
    }

    @Test
    fun resolveUnder_staysInsideBase() {
        val base = Files.createTempDirectory("tar-base").toFile()
        try {
            val ok = TarSafety.resolveUnder(base, "usr/bin/bash")
            assertTrue(ok.isSuccess)
            assertEquals(File(base, "usr/bin/bash").absolutePath, ok.getOrThrow().absolutePath)

            assertTrue(TarSafety.resolveUnder(base, "/etc/passwd").isFailure)
            assertTrue(TarSafety.resolveUnder(base, "a/../../b").isFailure)
            assertTrue(TarSafety.resolveUnder(base, "../escape").isFailure)
        } finally {
            base.deleteRecursively()
        }
    }
}
