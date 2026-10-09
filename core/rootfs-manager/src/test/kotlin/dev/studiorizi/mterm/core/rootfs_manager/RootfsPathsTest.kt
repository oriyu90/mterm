package dev.studiorizi.mterm.core.rootfs_manager

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsPathsTest {

    @Test
    fun activeRootfsDir_readsCurrentJson() {
        val filesDir = Files.createTempDirectory("mterm-paths").toFile()
        try {
            File(filesDir, "linux").mkdirs()
            File(filesDir, "linux/current.json").writeText(
                """{"channel":"STABLE","version":"13.7-r1","state":"READY"}""",
            )
            assertEquals(
                File(filesDir, "linux/distributions/debian/13.7-r1/rootfs"),
                RootfsManager.activeRootfsDir(filesDir),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun activeRootfsDir_fallsBackWhenAbsent() {
        val filesDir = Files.createTempDirectory("mterm-paths-fallback").toFile()
        try {
            assertEquals(
                File(filesDir, "linux/distributions/debian/current/rootfs"),
                RootfsManager.activeRootfsDir(filesDir),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun activeRootfsDir_rejectsTraversal() {
        val filesDir = Files.createTempDirectory("mterm-paths-evil").toFile()
        try {
            File(filesDir, "linux").mkdirs()
            File(filesDir, "linux/current.json").writeText(
                """{"channel":"STABLE","version":"../../evil","state":"READY"}""",
            )
            assertEquals(
                File(filesDir, "linux/distributions/debian/current/rootfs"),
                RootfsManager.activeRootfsDir(filesDir),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun installerUrlHelpers_followReleaseConvention() {
        val manifest = RootfsManifest(
            schema = 1, id = "debian-trixie-arm64", version = "13.7-r1",
            arch = "arm64", sha256 = "x", size = 1, minAppVersion = 10000,
            createdAt = "2026-10-10T00:00:00Z",
        )
        assertEquals(
            "https://github.com/oriyu90/mterm/releases/download/rootfs-13.7-r1/debian-trixie-arm64.tar.gz",
            RootfsInstaller.archiveUrlFor(manifest),
        )
        assertTrue(RootfsInstaller.manifestUrl().endsWith("debian-trixie-arm64.json"))
    }
}
