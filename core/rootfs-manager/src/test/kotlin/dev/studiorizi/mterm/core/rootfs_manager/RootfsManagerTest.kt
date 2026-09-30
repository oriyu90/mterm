package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootfsManagerTest {

    @Test
    fun currentInstall_nullWhenMissing() = runTest {
        val filesDir = Files.createTempDirectory("files-dir").toFile()
        try {
            val manager = RootfsManager(filesDir, ByteArray(32))
            assertNull(manager.currentInstall())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun currentInstall_roundTripAndEnsureInstalled() = runTest {
        val filesDir = Files.createTempDirectory("files-dir").toFile()
        try {
            val linuxDir = File(filesDir, "linux")
            linuxDir.mkdirs()
            File(linuxDir, "current.json").writeText(
                """{"channel":"STABLE","version":"13.7-r1","state":"READY"}""",
                Charsets.UTF_8,
            )
            val manager = RootfsManager(filesDir, ByteArray(32))
            val current = manager.currentInstall()
            assertEquals(RootfsChannel.STABLE, current?.channel)
            assertEquals("13.7-r1", current?.version)
            assertEquals(InstallState.READY, current?.state)
            assertEquals(manager.targetDir("13.7-r1"), current?.rootfsDir)

            val ensured = manager.ensureInstalled(RootfsChannel.STABLE)
            assertEquals(InstallState.READY, ensured.state)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun ensureInstalled_notInstalledWhenNothingReady() = runTest {
        val filesDir = Files.createTempDirectory("files-dir").toFile()
        try {
            val manager = RootfsManager(filesDir, ByteArray(32))
            val ensured = manager.ensureInstalled(RootfsChannel.STABLE)
            assertEquals(InstallState.NOT_INSTALLED, ensured.state)
            assertEquals(manager.targetDir(RootfsManager.PENDING_VERSION), ensured.rootfsDir)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun dirs_followExpectedLayout() {
        val filesDir = File("/tmp/files-test")
        val manager = RootfsManager(filesDir, ByteArray(32))
        assertEquals(File(filesDir, "linux/staging"), manager.stagingDir())
        assertEquals(
            File(filesDir, "linux/distributions/debian/13.7-r1/rootfs"),
            manager.targetDir("13.7-r1"),
        )
    }
}
