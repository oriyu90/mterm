package dev.studiorizi.mterm.core.linux_proot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePresetTest {

    @Test
    fun url_pointsAtPinnedLtsArm64Tarball() {
        assertEquals(
            "https://nodejs.org/dist/v24.21.0/node-v24.21.0-linux-arm64.tar.xz",
            NodePreset.url(),
        )
    }

    @Test
    fun ensureProfileSnippet_isIdempotent() {
        val home = Files.createTempDirectory("mterm-node-test").toFile()
        try {
            NodePreset.ensureProfileSnippet(home)
            NodePreset.ensureProfileSnippet(home)
            val text = File(home, ".profile").readText()
            assertEquals(1, "mterm node preset \\(managed\\)".toRegex().findAll(text).count())
            assertTrue(text.contains("${'$'}HOME/.local/node/bin"))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun nodeDir_livesUnderLocal() {
        val home = File("/data/home/user")
        assertEquals(File("/data/home/user/.local/node"), NodePreset.nodeDir(home))
    }
}
