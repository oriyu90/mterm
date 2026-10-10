package dev.studiorizi.mterm.full.backend

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeCliInstallerTest {

    private fun scripts(): Map<String, String> =
        BridgeCliInstaller.SCRIPT_NAMES.associateWith { "#!/bin/sh\n# $it\n" }

    @Test
    fun ensure_writesAndChmods() = runTest {
        val rootfs = Files.createTempDirectory("mterm-bridge-test").toFile()
        try {
            val updated = BridgeCliInstaller.ensure(rootfs, scripts()).getOrThrow()
            assertEquals(BridgeCliInstaller.SCRIPT_NAMES.size, updated)
            for (name in BridgeCliInstaller.SCRIPT_NAMES) {
                val file = File(rootfs, "usr/local/bin/$name")
                assertTrue("$name exists", file.isFile)
                assertTrue("$name executable", file.canExecute())
            }
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun ensure_idempotent() = runTest {
        val rootfs = Files.createTempDirectory("mterm-bridge-idem").toFile()
        try {
            BridgeCliInstaller.ensure(rootfs, scripts()).getOrThrow()
            assertEquals(0, BridgeCliInstaller.ensure(rootfs, scripts()).getOrThrow())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun ensure_missingScriptFails() = runTest {
        val rootfs = Files.createTempDirectory("mterm-bridge-missing").toFile()
        try {
            val result = BridgeCliInstaller.ensure(rootfs, emptyMap())
            assertTrue(result.isFailure)
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
