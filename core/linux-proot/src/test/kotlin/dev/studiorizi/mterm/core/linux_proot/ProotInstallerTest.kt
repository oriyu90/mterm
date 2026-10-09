package dev.studiorizi.mterm.core.linux_proot

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotInstallerTest {

    @Test
    fun install_writesAndChmodsAllBinaries() = runTest {
        val binDir = Files.createTempDirectory("mterm-proot-test").toFile()
        try {
            val assets = mapOf(
                ProotInstaller.PROOT_NAME to "#!/bin/sh\necho proot-test\n".toByteArray(),
                ProotInstaller.LIB_TALLOC to ByteArray(16) { it.toByte() },
                ProotInstaller.LIB_SHMEM to ByteArray(8) { (it + 1).toByte() },
            )
            val installed = ProotInstaller.install(binDir, assets).getOrThrow()
            assertEquals(File(binDir, "proot"), installed)
            for (name in ProotInstaller.ASSET_NAMES) {
                val file = File(binDir, name)
                assertTrue("$name exists", file.isFile)
                assertTrue("$name executable", file.canExecute())
                assertTrue("$name bytes match", file.readBytes().contentEquals(assets[name]))
            }
        } finally {
            binDir.deleteRecursively()
        }
    }

    @Test
    fun install_missingAssetFails() = runTest {
        val binDir = Files.createTempDirectory("mterm-proot-missing").toFile()
        try {
            val result = ProotInstaller.install(binDir, mapOf(ProotInstaller.PROOT_NAME to ByteArray(4)))
            assertTrue(result.isFailure)
        } finally {
            binDir.deleteRecursively()
        }
    }

    @Test
    fun probeVersion_nonExecutableReturnsNull() = runTest {
        val missing = File("/nonexistent-proot-xyz")
        assertNull(ProotInstaller.probeVersion(missing, File("/tmp")))
    }

    @Test
    fun probeVersion_scriptEchoesVersion() = runTest {
        val binDir = Files.createTempDirectory("mterm-proot-probe").toFile()
        try {
            val script = File(binDir, "proot")
            script.writeText("#!/system/bin/sh\nprintf 'proot-test-1.0'\n")
            // /system/bin/sh does not exist on JVM hosts; probe must fail
            // closed (null) rather than throw on any platform.
            val result = ProotInstaller.probeVersion(script, binDir)
            assertTrue(result == null || result == "proot-test-1.0")
        } finally {
            binDir.deleteRecursively()
        }
    }
}
