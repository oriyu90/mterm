package dev.studiorizi.mterm.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play separation guard: the remote module must never reference the
 * guest userland loader stacks or the privileged helper library.
 * Scans every Kotlin source under src/main/java and fails on any hit.
 */
class NoLoaderCodeTest {

    private val forbidden = listOf(
        "linux_proot",
        "linux_chroot",
        "rootfs_manager",
        "root_core",
        "libsu",
    )

    @Test
    fun `no loader code in remote module`() {
        val root = File("src/main/java")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val hit = forbidden.firstOrNull { token -> line.contains(token) }
                    if (hit != null) {
                        file.path + ":" + (index + 1) + ": found '" + hit + "'"
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue(
            "Forbidden loader references found:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
