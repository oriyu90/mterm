package dev.studiorizi.mterm.core.rootfs_manager

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarGzExtractorTest {

    /** Minimal USTAR writer for fixtures (name, mode, mtime, type, link, data). */
    private fun ustar(
        name: String,
        mode: Int = 0b110100100,
        mtime: Long = 1_700_000_000L,
        type: Char = '0',
        link: String = "",
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val header = ByteArray(512)
        fun put(off: Int, len: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            bytes.copyInto(header, off, 0, minOf(bytes.size, len))
        }
        fun octal(off: Int, len: Int, value: Long) {
            put(off, len, value.toString(8).padStart(len - 1, '0') + "\u0000")
        }
        // Long names ride on the prefix field (name <= 100 here).
        put(0, 100, name)
        octal(100, 8, mode.toLong())
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, data.size.toLong())
        octal(136, 12, mtime)
        header[156] = type.code.toByte()
        put(157, 100, link)
        put(257, 6, "ustar")
        put(263, 2, "00")
        var checksum = 0
        for (i in 0 until 512) {
            checksum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xFF
        }
        put(148, 8, checksum.toString(8).padStart(6, '0') + "\u0000 ")
        val body = ByteArray(((data.size + 511) / 512) * 512)
        data.copyInto(body)
        return header + body
    }

    private fun gzip(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (block in blocks) gz.write(block)
            gz.write(ByteArray(1024)) // end-of-archive zeros
        }
        return out.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("mterm-tar-test").toFile()

    @Test
    fun extract_filesDirsSymlinksModes() = runTest {
        val archive = gzip(
            ustar("etc/", type = '5'),
            ustar("etc/resolv.conf", data = "nameserver 1.1.1.1\n".toByteArray()),
            ustar("bin/tool", mode = 0b111101101, data = "#!/bin/sh\n".toByteArray()),
            ustar("bin/link", type = '2', link = "tool"),
        )
        val dir = tempDir()
        try {
            val archiveFile = File(dir, "a.tar.gz")
            archiveFile.writeBytes(archive)
            val base = File(dir, "root")
            val stats = TarGzExtractor.extract(archiveFile, base).getOrThrow()
            assertEquals(4, stats.entries)
            assertEquals("nameserver 1.1.1.1\n", File(base, "etc/resolv.conf").readText())
            assertTrue(File(base, "bin/tool").canExecute())
            val link = File(base, "bin/link")
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(0, stats.skipped)
            assertTrue(stats.bytes > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extract_rejectsTraversalAndAbsolute() = runTest {
        val archive = gzip(
            ustar("../../evil", data = "x".toByteArray()),
            ustar("/abs", data = "x".toByteArray()),
            ustar("ok.txt", data = "ok".toByteArray()),
        )
        val dir = tempDir()
        try {
            val archiveFile = File(dir, "a.tar.gz")
            archiveFile.writeBytes(archive)
            val base = File(dir, "root")
            val stats = TarGzExtractor.extract(archiveFile, base).getOrThrow()
            assertEquals("ok", File(base, "ok.txt").readText())
            assertEquals(2, stats.skipped)
            assertTrue(!File(dir, "evil").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extract_rejectsSymlinkEscape() = runTest {
        val archive = gzip(
            ustar("evil-link", type = '2', link = "/etc/passwd"),
            ustar("dir/", type = '5'),
            ustar("dir/up-link", type = '2', link = "../../outside"),
        )
        val dir = tempDir()
        try {
            val archiveFile = File(dir, "a.tar.gz")
            archiveFile.writeBytes(archive)
            val base = File(dir, "root")
            val stats = TarGzExtractor.extract(archiveFile, base).getOrThrow()
            assertTrue(!File(base, "evil-link").exists())
            assertTrue(!File(base, "dir/up-link").exists())
            assertEquals(2, stats.skipped)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extract_rejectsNonGzip() = runTest {
        val dir = tempDir()
        try {
            val archiveFile = File(dir, "a.tar.gz")
            archiveFile.writeText("plain text, not gzip")
            val result = TarGzExtractor.extract(archiveFile, File(dir, "root"))
            assertTrue(result.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extract_truncatedEntryFails() = runTest {
        val full = ustar("big.bin", data = ByteArray(10_000) { 1 })
        val archiveFile = File(tempDir(), "a.tar.gz")
        try {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { gz -> gz.write(full.copyOfRange(0, 700)) }
            archiveFile.writeBytes(out.toByteArray())
            val result = TarGzExtractor.extract(archiveFile, File(archiveFile.parentFile, "root"))
            assertTrue(result.isFailure)
        } finally {
            archiveFile.parentFile.deleteRecursively()
        }
    }
}
